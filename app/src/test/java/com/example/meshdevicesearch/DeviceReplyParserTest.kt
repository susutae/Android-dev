package com.example.meshdevicesearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceReplyParserTest {
    @Test fun parsesDocumentExample() {
        assertEquals(
            DiscoveredDevice("192.168.10.6", "255.255.255.0"),
            DeviceReplyParser.parse("""{"device":"mesh","method":"response","ipAddr":"192.168.10.6","nwMask":"255.255.255.0"}"""),
        )
    }

    @Test fun rejectsWrongMessageType() {
        assertNull(DeviceReplyParser.parse("""{"device":"mesh","method":"discover","ipAddr":"192.168.10.6","nwMask":"255.255.255.0"}"""))
    }

    @Test fun rejectsInvalidAddressAndMask() {
        assertNull(DeviceReplyParser.parse("""{"device":"mesh","method":"response","ipAddr":"192.168.10.999","nwMask":"255.0.255.0"}"""))
    }

    @Test fun rejectsUriInsteadOfIpv4Address() {
        assertNull(DeviceReplyParser.parse("""{"device":"mesh","method":"response","ipAddr":"example.com/path","nwMask":"255.255.255.0"}"""))
    }
}
