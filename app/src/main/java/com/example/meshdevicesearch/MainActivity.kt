package com.example.meshdevicesearch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
                addView(webManagementControls(device))
            }
            card.addView(content)
            resultsContainer.addView(card)
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
            switchMinWidth = dp(56)
            thumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(26), dp(26))
                setColor(Color.WHITE)
            }
            trackDrawable = GradientDrawable().apply {
                setSize(dp(56), dp(34))
                cornerRadius = dp(17).toFloat()
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
