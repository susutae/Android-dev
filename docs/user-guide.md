# AGIL Mesh Device Search user guide

AGIL Mesh Device Search finds mesh radios on your local network. Use it to open a radio’s web interface, check or change its Web Management setting, and inspect live links and node telemetry.

## Install and connect

1. Install the AGIL Mesh APK supplied by your administrator or project team. The project’s debug build is at `app/build/outputs/apk/debug/app-debug.apk`. The app supports Android 6.0 and later.
2. Connect your phone or tablet to the same local network as the radios over Wi-Fi or Ethernet. The interface needs an IPv4 address. A DHCP-assigned address works.
3. Open **AGIL Mesh** from the app launcher.

## Discover radios

1. Under **Device discovery**, select the **Network interface** connected to the radios. The entry shows the interface name and IPv4 address, for example `eth0 - 192.168.10.89`.
2. Tap **Refresh interfaces** after connecting an adapter, switching networks, or changing an IP address.
3. Tap **Scan for devices**. The app listens for replies for about five seconds. Tap **Stop scan** to end the scan early.
4. Under **Discovered devices**, each result shows the radio’s IP address and subnet mask. Tap the IP address or **Open in browser** to open the radio’s web interface in your default browser.

Discovery uses a local UDP broadcast on port 7000. It normally stays on the selected interface’s local network. A radio can appear in the results even if the phone cannot reach its IP address over HTTP.

## Manage web access

The **Web Management** section automatically checks the radio’s current setting when its card appears. Tap **Refresh status** to check again.

1. Move the switch toward **Enable** or **Disable**. Green indicates enable, red indicates disable, and gray indicates that the switch is unavailable.
2. Tap **Apply change** to send the setting to the radio. Moving the switch alone leaves the radio’s setting unchanged.
3. After the radio accepts the change, tap **Refresh status** to confirm it. Disabling web access can make the browser interface unavailable.

If the initial status check fails, the switch stays disabled. Confirm the network connection and try **Refresh status** again.

## View live topology

1. On a discovered radio’s card, tap **View network topology**. The graph shows the nodes and links reported by that radio.
2. Follow each arrow to read the link direction. The numbers beside the curves show signal-to-noise ratio (SNR) in dB. Links in opposite directions can have different readings.
3. Tap a node or use **Select a node** to open **Node Information**. On narrow screens, scroll below the graph. On wider screens, look beside it.
4. Tap **Close** to exit topology.

The graph requests a new snapshot about once per second while open. If a request fails after a successful refresh, the app keeps the last snapshot and displays a refresh warning.

| SNR | Quality |
| --- | --- |
| 27 dB or higher | Excellent, cyan |
| 13 to below 27 dB | Good, green |
| 8 to below 13 dB | Average, yellow |
| 3 to below 8 dB | Fair, orange |
| Below 3 dB, when reported | Poor, red |

### Read Node Information

- **Identity** shows the node name when reported, plus its node ID and IP address.
- **Device receive** and **Device transmit** show the selected radio’s live PHY rates in Mbps. A very small nonzero rate can round to `0.00 Mbps`.
- **FPGA temperature** and **Resource use** show the latest available device readings.
- **Peer links** groups incoming and outgoing SNR by peer. Use **Weakest first** or **Strongest first** to switch the order. **Range** is estimated from device-reported transmission delay and is shown in kilometers.
- **GPS and coordinates** shows GPS status, latitude, longitude, and altitude when available.

**Unavailable** means the app has no usable reading for that field. **No link** means the snapshot has no active SNR reading for that direction.

## Troubleshooting

| What you see | What to check |
| --- | --- |
| No network interface appears | Check Wi-Fi or Ethernet, confirm the phone has an IPv4 address, then tap **Refresh interfaces**. |
| The scan finishes with no replies | Confirm the correct interface is selected and the radios are on the same local broadcast network. A router, separate VLAN, or Wi-Fi client isolation can block discovery. |
| Discovery works but status or topology times out | Check that the phone can reach the radio’s IP over the selected interface. If the connection or address changed, refresh interfaces and scan again. |
| The browser opens but cannot load the radio | Check **Web Management** and the phone’s route to the radio. The external browser uses Android’s routing, which can differ from the interface used for the scan. |
| Some rates, ranges, or GPS fields are unavailable | The selected radio may be unreachable or may omit those readings. Check the connection and select the node again. |
