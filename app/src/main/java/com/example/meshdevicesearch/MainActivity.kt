package com.example.meshdevicesearch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.meshdevicesearch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var interfaces = emptyList<NetworkInterfaceChoice>()
    private val viewModel: SearchViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(UdpDeviceDiscovery(applicationContext)) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        arrangeLayout()
        loadInterfaces()

        binding.refreshInterfacesButton.setOnClickListener { loadInterfaces() }
        binding.searchButton.setOnClickListener { startSearch() }
        binding.stopButton.setOnClickListener { viewModel.stop() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun arrangeLayout() = with(binding) {
        val configuration = resources.configuration
        val shortWindow = configuration.screenHeightDp < 500
        if (shortWindow) {
            brandHeader.visibility = View.GONE
        }
        if (configuration.screenWidthDp < 700) return@with

        if (configuration.screenWidthDp >= 1240) {
            screenContent.layoutParams = screenContent.layoutParams.apply { width = dp(1200) }
        }
        val columns = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        fun addColumn(weight: Float, startMargin: Int, vararg children: View) {
            val column = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            children.forEach { child ->
                val params = child.layoutParams
                screenContent.removeView(child)
                column.addView(child, params)
            }
            columns.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                marginStart = dp(startMargin)
            })
        }
        addColumn(1f, 0, discoveryPanel, statusPanel)
        addColumn(1.25f, 16, devicesHeader, resultsContainer)
        screenContent.addView(columns, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun applySystemBarInsets() {
        val initialLeft = binding.mainScroll.paddingLeft
        val initialTop = binding.mainScroll.paddingTop
        val initialRight = binding.mainScroll.paddingRight
        val initialBottom = binding.mainScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainScroll) { view, windowInsets ->
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.mainScroll)
    }

    private fun loadInterfaces() {
        val previousLabel = binding.interfaceInput.text.toString()
        val previousInterface = interfaces.firstOrNull { it.label == previousLabel }
        binding.interfaceInput.dismissDropDown()
        interfaces = NetworkInterfaceProvider(this).activeIpv4Interfaces()
        binding.interfaceInput.setAdapter(
            ArrayAdapter(this, R.layout.item_network_interface, interfaces.map { it.label }),
        )
        val selected = interfaces.firstOrNull { it.label == previousLabel }
            ?: interfaces.firstOrNull { it.interfaceName == previousInterface?.interfaceName }
            ?: interfaces.firstOrNull()
        binding.interfaceInput.setText(selected?.label.orEmpty(), false)
        binding.searchButton.isEnabled = selected != null && !viewModel.state.value.isSearching
        binding.interfaceLayout.error = if (interfaces.isEmpty()) "No active IPv4 interface found" else null
    }

    private fun startSearch() {
        binding.interfaceLayout.error = null
        val selected = interfaces.firstOrNull { it.label == binding.interfaceInput.text.toString() }
        if (selected == null) {
            binding.interfaceLayout.error = "Select an active interface"
            return
        }
        binding.resultsContainer.removeAllViews()
        viewModel.search(SearchRequest(selected))
    }

    private fun render(state: SearchUiState) = with(binding) {
        searchButton.isEnabled = !state.isSearching && interfaces.isNotEmpty()
        refreshInterfacesButton.isEnabled = !state.isSearching
        stopButton.isEnabled = state.isSearching
        searchButton.visibility = if (state.isSearching) View.GONE else View.VISIBLE
        stopButton.visibility = if (state.isSearching) View.VISIBLE else View.GONE
        interfaceInput.isEnabled = !state.isSearching
        statusText.text = state.status
        statusDot.backgroundTintList = ColorStateList.valueOf(
            getColor(
                when (state.phase) {
                    SearchPhase.IDLE -> R.color.mesh_muted
                    SearchPhase.SEARCHING -> R.color.mesh_cyan
                    SearchPhase.SUCCESS -> R.color.mesh_success
                    SearchPhase.EMPTY, SearchPhase.STOPPED -> R.color.mesh_warning
                    SearchPhase.ERROR -> R.color.mesh_error
                },
            ),
        )
        searchProgress.visibility = if (state.isSearching) View.VISIBLE else View.GONE
        renderDevices(state.devices)
    }

    private fun renderDevices(devices: List<DiscoveredDevice>) = with(binding) {
        devicesHeader.text = if (devices.isEmpty()) getString(R.string.devices_found)
        else getString(R.string.devices_found_count, devices.size)
        if (devices.isEmpty()) {
            resultsContainer.removeAllViews()
            resultsText.text = getString(R.string.no_devices)
            resultsContainer.addView(resultsText)
            return@with
        }
        if (resultsText.parent == resultsContainer) resultsContainer.removeView(resultsText)
        devices.forEachIndexed { index, device ->
            if (index < resultsContainer.childCount) return@forEachIndexed
            val card = MaterialCardView(this@MainActivity).apply {
                radius = dp(12).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.mesh_card))
                strokeColor = getColor(R.color.mesh_border)
                strokeWidth = dp(1)
                if (index > 0) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            }
            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(deviceField(getString(R.string.ip_address), device.ipAddress, true).apply {
                    isFocusable = true
                    contentDescription = getString(R.string.open_device_in_browser, device.ipAddress)
                    setOnClickListener { openDeviceInBrowser(device.ipAddress) }
                })
                addView(deviceField(getString(R.string.subnet_mask), device.subnetMask, false))
                addView(resultButton(R.string.open_web_interface, R.drawable.ic_action_external).apply {
                    contentDescription = getString(R.string.open_device_in_browser, device.ipAddress)
                    setOnClickListener { openDeviceInBrowser(device.ipAddress) }
                })
                addView(resultButton(R.string.view_topology, R.drawable.ic_action_topology).apply {
                    setOnClickListener { showTopology(device) }
                })
                addView(webManagementControls(device))
            }
            card.addView(content)
            resultsContainer.addView(card)
        }
    }

    private fun showTopology(device: DiscoveredDevice) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        val status = TextView(this).apply {
            setTextColor(getColor(R.color.mesh_text))
            setText(R.string.topology_loading)
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var latest: NetworkTopology? = null
        var selectedId: Int? = null
        var telemetry: NodeTelemetry? = null
        var telemetryJob: kotlinx.coroutines.Job? = null
        var strongestFirst = false
        var rendered: List<Any?>? = null

        fun updateDetails() {
            val topology = latest ?: return
            val index = topology.nodes.indexOfFirst { it.id == selectedId }
            val key = listOf(topology, selectedId, telemetry, strongestFirst)
            if (rendered == key) return
            rendered = key
            details.removeAllViews()
            if (index < 0) return
            val node = topology.nodes[index]
            fun LinearLayout.label(text: String, color: Int = R.color.mesh_text, bold: Boolean = false) {
                addView(TextView(context).apply {
                    this.text = text
                    setTextColor(getColor(color))
                    if (bold) setTypeface(typeface, Typeface.BOLD)
                })
            }
            fun panel() = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_mesh_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val identity = panel()
            identity.label(getString(R.string.node_information), R.color.mesh_cyan, true)
            identity.label(node.name.ifBlank { telemetry?.name.orEmpty() }
                .ifBlank { getString(R.string.node_number, node.id) }, bold = true)
            identity.label(getString(R.string.topology_node, node.id, node.ip.ifBlank { "—" }), R.color.mesh_muted)
            val metrics = listOf(
                R.string.node_receive to telemetry?.rx?.let { getString(R.string.node_mbps, it) },
                R.string.node_transmit to telemetry?.tx?.let { getString(R.string.node_mbps, it) },
                R.string.node_temperature to telemetry?.temperature?.let { getString(R.string.node_celsius, it) },
                R.string.node_resource_label to node.resource?.let { getString(R.string.node_percent, it.coerceIn(0.0, 1.0) * 100) },
            )
            fun addTiles(target: LinearLayout, values: List<Pair<Int, String?>>) {
                values.chunked(2).forEach { cells ->
                target.addView(LinearLayout(this).apply {
                    isBaselineAligned = false
                    cells.forEachIndexed { position, (title, value) ->
                        addView(panel().apply {
                            label(getString(title), R.color.mesh_muted)
                            label(value ?: getString(R.string.node_unavailable), bold = true)
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            topMargin = dp(8)
                            if (position > 0) marginStart = dp(8)
                        })
                    }
                })
                }
            }
            addTiles(identity, metrics)
            details.addView(identity)
            val links = topology.links.filter { it.from == index || it.to == index }
                .groupBy { if (it.from == index) it.to else it.from }
            val sorted = links.entries.sortedBy { (_, values) -> values.minOf { it.snr } }
            details.label(getString(R.string.node_peer_count, links.size), bold = true)
            if (links.size > 1) {
                details.addView(resultButton(
                    if (strongestFirst) R.string.node_sort_strong else R.string.node_sort_weak,
                    R.drawable.ic_action_topology,
                ).apply { setOnClickListener { strongestFirst = !strongestFirst; updateDetails() } })
            }
            (if (strongestFirst) sorted.asReversed() else sorted).forEach { (peerIndex, readings) ->
                val peer = topology.nodes[peerIndex]
                val card = panel()
                card.label(peer.name.ifBlank { getString(R.string.node_number, peer.id) }, bold = true)
                fun direction(label: Int, reading: TopologyLink?) {
                    card.label(getString(label), R.color.mesh_muted)
                    val snr = reading?.snr
                    val band = when {
                        snr == null -> R.string.node_no_link
                        snr >= 27 -> R.string.node_excellent
                        snr >= 13 -> R.string.node_good
                        snr >= 8 -> R.string.node_average
                        snr >= 3 -> R.string.node_fair
                        else -> R.string.node_poor
                    }
                    card.label(if (snr == null) getString(band) else getString(R.string.node_snr, snr, getString(band)),
                        if (snr == null) R.color.mesh_muted else R.color.mesh_cyan, true)
                }
                direction(R.string.node_incoming, readings.firstOrNull { it.to == index })
                direction(R.string.node_outgoing, readings.firstOrNull { it.from == index })
                val range = telemetry?.ranges?.get(peer.id) ?: when (device.ipAddress) {
                    node.ip -> topology.sourceRanges[peer.id]
                    peer.ip -> topology.sourceRanges[node.id]
                    else -> null
                }
                card.label(getString(R.string.node_range, range?.let { getString(R.string.node_km, it) }
                    ?: getString(R.string.node_unavailable)), R.color.mesh_muted)
                details.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            val gps = panel()
            gps.label(getString(R.string.node_gps_heading), bold = true)
            val gpsStatus = when (telemetry?.gpsStatus) {
                1 -> R.string.node_gps_locked
                2 -> R.string.node_gps_warning
                0 -> R.string.node_gps_inactive
                else -> R.string.node_unavailable
            }
            val hasCoordinates = node.latitude != null && node.longitude != null &&
                !(node.latitude == -90.0 && node.longitude == -180.0)
            addTiles(gps, listOf(
                R.string.node_gps_status to getString(gpsStatus),
                R.string.node_latitude to node.latitude?.takeIf { hasCoordinates }?.let { getString(R.string.node_degrees, it) },
                R.string.node_longitude to node.longitude?.takeIf { hasCoordinates }?.let { getString(R.string.node_degrees, it) },
                R.string.node_altitude_label to node.altitude?.takeIf { hasCoordinates }?.let { getString(R.string.node_meters, it) },
            ))
            details.addView(gps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        val graph = TopologyGraph(this, device.ipAddress).apply { visibility = View.GONE }
        content.addView(status)
        val configuration = resources.configuration
        val wide = configuration.screenWidthDp >= 680
        val graphHeight = (configuration.screenHeightDp * if (wide) 0.48f else 0.34f)
            .toInt().coerceIn(130, 400)
        val picker = resultButton(R.string.topology_pick_node, R.drawable.ic_action_topology).apply {
            visibility = View.GONE
        }
        val detailScroll = ScrollView(this).apply {
            isNestedScrollingEnabled = true
            addView(details)
        }
        val inspector = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(picker)
            addView(detailScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (wide) dp((configuration.screenHeightDp - 156).coerceAtLeast(132))
                else LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            visibility = View.GONE
        }
        if (wide) {
            content.addView(LinearLayout(this).apply {
                isBaselineAligned = false
                addView(graph, LinearLayout.LayoutParams(0, dp(graphHeight), 1.2f))
                addView(inspector, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                })
            })
        } else {
            content.addView(graph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(graphHeight)))
            content.addView(inspector)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.topology_title, device.ipAddress))
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.topology_close, null)
            .create()
        fun selectNode(id: Int) {
            telemetryJob?.cancel()
            telemetry = null
            selectedId = id
            latest?.let { graph.update(it, id) }
            updateDetails()
            detailScroll.scrollTo(0, 0)
            val node = latest?.nodes?.firstOrNull { it.id == id } ?: return
            telemetryJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (dialog.isShowing) {
                        val current = latest?.nodes?.firstOrNull { it.id == id && it.ip == node.ip } ?: break
                        try {
                            telemetry = NetworkTopology.telemetry(
                                device, current.copy(name = current.name.ifBlank { telemetry?.name.orEmpty() }),
                            )
                            updateDetails()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Leave unavailable readings visible.
                        }
                        kotlinx.coroutines.delay(1_000)
                    }
                }
            }
        }
        graph.onNodeSelected = ::selectNode
        picker.setOnClickListener {
            val nodes = latest?.nodes.orEmpty().sortedBy { it.id }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.topology_pick_node)
                .setItems(nodes.map { getString(R.string.topology_node, it.id, it.ip) }.toTypedArray()) { _, index ->
                    selectNode(nodes[index].id)
                }.show()
        }
        dialog.show()
        if (wide) {
            dialog.window?.setLayout(dp((configuration.screenWidthDp - 40).coerceAtMost(960)),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val job = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (dialog.isShowing) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        val topology = NetworkTopology.read(device)
                        latest = topology
                        graph.update(topology, selectedId)
                        graph.visibility = if (topology.nodes.isEmpty()) View.GONE else View.VISIBLE
                        picker.visibility = graph.visibility
                        inspector.visibility = graph.visibility
                        status.text = if (topology.nodes.isEmpty()) getString(R.string.topology_empty)
                            else getString(R.string.topology_live_summary, topology.nodes.size, topology.links.size)
                        updateDetails()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        status.text = getString(
                            if (latest == null) R.string.topology_failed else R.string.topology_stale,
                            error.localizedMessage ?: error.javaClass.simpleName,
                        )
                    }
                    kotlinx.coroutines.delay((1_000 - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                }
            }
        }
        dialog.setOnDismissListener {
            job.cancel()
            telemetryJob?.cancel()
        }
    }

    private fun webManagementControls(device: DiscoveredDevice) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
        val status = TextView(context).apply {
            setTextColor(getColor(R.color.mesh_text))
            setText(R.string.web_status_unknown)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(TextView(context).apply {
            setText(R.string.web_management)
            setTextColor(getColor(R.color.mesh_text))
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(status)
        addView(TextView(context).apply {
            setText(R.string.web_management_help)
            setTextColor(getColor(R.color.mesh_muted))
            setPadding(0, dp(4), 0, dp(4))
        })
        val toggle = SwitchCompat(context).apply {
            contentDescription = getString(R.string.web_management)
            minimumHeight = dp(48)
            switchMinWidth = dp(60)
            thumbDrawable = object : GradientDrawable() {
                private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

                override fun getIntrinsicWidth() = dp(32)
                override fun getIntrinsicHeight() = dp(32)

                override fun draw(canvas: Canvas) {
                    val radius = (minOf(bounds.width(), bounds.height(), dp(32)) / 2f - dp(4)).coerceAtLeast(0f)
                    canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, thumbPaint)
                }
            }
            trackDrawable = GradientDrawable().apply {
                setSize(dp(64), dp(32))
                cornerRadius = dp(16).toFloat()
            }
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.mesh_border), getColor(R.color.mesh_success), getColor(R.color.mesh_error)),
            )
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(webToggleLabel(R.string.web_disable))
            addView(toggle)
            addView(webToggleLabel(R.string.web_enable))
        })
        val get = resultButton(R.string.web_get, R.drawable.ic_action_refresh)
        val apply = resultButton(R.string.web_apply, R.drawable.ic_action_check, primary = true).apply { isEnabled = false }
        addView(get)
        addView(apply)
        var busy = false
        var confirmed: Boolean? = null
        toggle.setOnCheckedChangeListener { _, checked ->
            apply.isEnabled = !busy && confirmed != null && checked != confirmed
        }

        fun request(enabled: Boolean?) {
            busy = true
            listOf(get, apply, toggle).forEach { it.isEnabled = false }
            status.setText(R.string.web_request_pending)
            lifecycleScope.launch {
                try {
                    val result = WebManagementClient.request(device, enabled)
                    if (result != null) {
                        confirmed = result
                        toggle.isChecked = result
                        status.setText(if (result) R.string.web_status_enabled else R.string.web_status_disabled)
                    } else {
                        confirmed = enabled
                        status.setText(R.string.web_post_accepted)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    status.text = getString(R.string.web_request_failed, error.localizedMessage ?: error.javaClass.simpleName)
                } finally {
                    busy = false
                    get.isEnabled = true
                    toggle.isEnabled = confirmed != null
                    apply.isEnabled = confirmed != null && toggle.isChecked != confirmed
                }
            }
        }
        get.setOnClickListener { request(null) }
        apply.setOnClickListener { request(toggle.isChecked) }
        request(null)
    }

    private fun resultButton(label: Int, iconResource: Int, primary: Boolean = false) = MaterialButton(this).apply {
        setText(label)
        isAllCaps = false
        letterSpacing = 0.02f
        setIconResource(iconResource)
        iconSize = dp(20)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        minimumHeight = dp(48)
        cornerRadius = dp(10)
        strokeWidth = if (primary) 0 else dp(1)
        strokeColor = ColorStateList.valueOf(getColor(R.color.mesh_border))
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        backgroundTintList = ColorStateList(states, intArrayOf(
            getColor(R.color.mesh_panel), getColor(if (primary) R.color.mesh_cyan else R.color.mesh_input),
        ))
        setTextColor(ColorStateList(states, intArrayOf(
            getColor(R.color.mesh_muted), getColor(if (primary) R.color.mesh_input else R.color.mesh_cyan),
        )))
        iconTint = textColors
    }

    private fun webToggleLabel(label: Int) = TextView(this).apply {
        setText(label)
        setTextColor(getColor(R.color.mesh_text))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun deviceField(label: String, value: String, first: Boolean) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!first) setPadding(0, dp(12), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(getColor(R.color.mesh_muted))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = if (first) 18f else 16f
                setTextColor(getColor(if (first) R.color.mesh_cyan else R.color.mesh_text))
                setPadding(0, dp(3), 0, 0)
                if (!first) setTextIsSelectable(true)
            })
        }

    private fun openDeviceInBrowser(ipAddress: String) {
        val uri = Uri.Builder().scheme("http").encodedAuthority(ipAddress).build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_web_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
