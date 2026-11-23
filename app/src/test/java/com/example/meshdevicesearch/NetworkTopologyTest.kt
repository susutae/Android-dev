package com.example.meshdevicesearch

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTopologyTest {
    @Test fun preservesMatrixOrderAndSkipsMissingLinks() {
        val topology = NetworkTopology.parse(
            """{"nodeInfos":[{"id":36},{"id":33},{"id":35}],"linkQuality":[[-10,27,null],[8,-10,-10],[]]}"""
        )
        assertEquals(listOf(36, 33, 35), topology.nodes.map { it.id })
        assertEquals(listOf(TopologyLink(0, 1, 27.0), TopologyLink(1, 0, 8.0)), topology.links)
    }
}
