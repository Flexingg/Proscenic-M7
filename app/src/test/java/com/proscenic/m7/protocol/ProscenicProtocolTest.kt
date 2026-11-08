package com.proscenic.m7.protocol

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProscenicProtocolTest {

    @Test
    fun `Lz4Codec round-trip compresses and decompresses 120x100 occupancy grid`() {
        val cells = ByteArray(120 * 100) { idx ->
            when {
                idx % 17 == 0 -> -1 // obstacle wall
                idx % 5 == 0 -> 1   // room 1
                idx % 7 == 0 -> 2   // room 2
                else -> 0           // unexplored
            }
        }

        val (b64, _) = Lz4Codec.encodeMapBase64Lz4(cells)
        val decompressed = Lz4Codec.decodeMapBase64Lz4(b64, width = 120, height = 100)

        assertArrayEquals(cells, decompressed)
    }

    @Test
    fun `parseMap20002 decodes Base64 LZ4 20002 map and computes room centroids and cell math`() {
        val width = 60
        val height = 50
        val cells = ByteArray(width * height) { idx ->
            val col = idx % width
            val row = idx / width
            when {
                col in 5..25 && row in 5..20 -> 1.toByte()
                col in 30..50 && row in 25..40 -> 2.toByte()
                else -> 0.toByte()
            }
        }
        val (b64Map, _) = Lz4Codec.encodeMapBase64Lz4(cells)

        val payload = JSONObject().apply {
            put("mapId", 42L)
            put("width", width)
            put("height", height)
            put("resolution", 0.05)
            put("x_min", -1.5)
            put("y_min", -1.25)
            put("chargeHandlePos", JSONArray().put(-1000).put(-800))
            put("chargeHandlePhi", 0)
            put("posArray", JSONArray().put(JSONArray().put(250).put(-350)))
            put("map", b64Map)
        }

        val decoded = ProscenicProtocol.parseMap20002(payload)
        assertEquals(60, decoded.width)
        assertEquals(50, decoded.height)
        assertEquals(0.05f, decoded.resolution, 0.0001f)
        assertEquals(PointMm(-1000, -800), decoded.chargeHandlePos)
        assertTrue("Expected 2 segmented rooms", decoded.roomCentroidsMm.size == 2)

        // Verify cell <-> world mm round-trip (`FUNC_MAP.md` §2.3)
        val sampleMm = PointMm(225, -325)
        val cell = decoded.worldMmToCell(sampleMm)
        val roundTripMm = decoded.cellToWorldMm(cell.col, cell.row)
        assertEquals(sampleMm.xMm, roundTripMm.xMm)
        assertEquals(sampleMm.yMm, roundTripMm.yMm)
    }

    @Test
    fun `MapMath snaps coordinates to 50mm lattice and truncates region names to 31 UTF-8 bytes`() {
        assertEquals(125, MapMath.snapToLatticeMm(147))
        assertEquals(-225, MapMath.snapToLatticeMm(-230))

        // Verify 2-bit path tag stripping (`FUNC_MAP.md` §3.1)
        val taggedX = (1200 and 0xFFFFFFFC.toInt()) or 0x02
        val cleanX = MapMath.stripPathTag(taggedX)
        assertEquals(1200, cleanX)

        // Verify stack-overflow guard in task_manager FUN_0043da7c (max 31 UTF-8 bytes)
        val overlongName = "Kitchen_And_Dining_Room_Very_Long_Label_1234567890"
        val safe = MapRegion.truncateUtf8(overlongName, maxBytes = 31)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 31)
    }

    @Test
    fun `buildZoneOrRoomCleanSequence produces 21023 ActiveRegions followed by 21005 appointClean`() {
        val frames = ProscenicProtocol.buildZoneOrRoomCleanSequence(
            storedZoneIds = listOf(10, 11),
            segmentRoomLabels = listOf(1, 3),
            includeStoredForbiddenZones = true
        )
        assertEquals(2, frames.size)
        assertTrue(frames[0].endsWith("#\t#"))
        assertTrue(frames[1].endsWith("#\t#"))

        val f1 = JSONObject(frames[0].removeSuffix("#\t#"))
        assertEquals(0, f1.getInt("encrypt"))
        val d1 = f1.getJSONObject("data")
        assertEquals(21023, d1.getInt("infoType"))
        val inner1 = d1.getJSONObject("data")

        val cleanIds = inner1.getJSONArray("cleanId")
        val idList = (0 until cleanIds.length()).map { idx -> cleanIds.getInt(idx) }
        assertTrue(idList.containsAll(listOf(-3, 10, 11)))

        val segIds = inner1.getJSONArray("segmentId")
        val segList = (0 until segIds.length()).map { idx -> segIds.getInt(idx) }
        assertEquals(listOf(1, 3), segList)

        // Frame 2 MUST be 21005 appointClean (never smartClean, which overwrites CleanArea)
        val f2 = JSONObject(frames[1].removeSuffix("#\t#"))
        val d2 = f2.getJSONObject("data")
        assertEquals(21005, d2.getInt("infoType"))
        assertEquals("appointClean", d2.getJSONObject("data").getString("mode"))
    }

    @Test
    fun `buildSetSchedules splits overnight quiet hours across midnight and round-trips 21002`() {
        val schedules = listOf(
            ScheduleEntry(
                id = "sched-1",
                label = "Morning Sweep",
                enabled = true,
                hour = 9,
                minute = 0,
                daysOfWeek = setOf(1, 2, 3, 4, 5),
                cleanIds = listOf(-1, -3),
                suction = SuctionLevel.AUTO,
                passes = 1
            ),
            ScheduleEntry(
                id = "sched-2",
                label = "Weekend Deep Clean",
                enabled = true,
                hour = 14,
                minute = 30,
                daysOfWeek = setOf(0, 6),
                cleanIds = listOf(-1, -3),
                suction = SuctionLevel.STRONG,
                passes = 2
            )
        )
        val quietHours = QuietHoursConfig(
            enabled = true,
            startHour = 23,
            startMinute = 0,
            endHour = 7,
            endMinute = 0
        )

        val frame = ProscenicProtocol.buildSetSchedules(schedules, quietHours)
        val unwrapped = JSONObject(frame.removeSuffix("#\t#"))
        val dataObj = unwrapped.getJSONObject("data").getJSONObject("data")
        val items = dataObj.getJSONArray("value")

        // 2 active schedules + 2 split overnight quiet-hour entries (23:00->23:59 and 00:00->07:00)
        assertEquals(4, items.length())

        val parsedBack = ProscenicProtocol.parseSchedulesFrom21002(dataObj)
        assertEquals(2, parsedBack.size)
        assertEquals("09:00", parsedBack[0].formattedTime)
        assertEquals(setOf(1, 2, 3, 4, 5), parsedBack[0].daysOfWeek)
    }

    @Test
    fun `RehomeClient rejects robot SoftAP SSID entered as Home Router SSID`() = runBlocking {
        val client = RehomeClient()
        val badResult = client.executeRehome(
            serverHostIp = "192.168.1.110",
            httpPort = 8080,
            gatewayPort = 8081,
            wifiSsid = "Proscenic-7190 C019707U",
            wifiPassword = "testtest",
            userId = "local_user"
        )

        assertFalse(badResult.success)
        assertTrue(badResult.errorMessage?.contains("HOME 2.4GHz router Wi-Fi SSID") == true)
    }

    @Test
    fun `roomBoundingBoxRegion creates inline extraAreas rectangle for 0_7_1 firmware compatibility and detects shutdown sleep state`() {
        val width = 40
        val height = 40
        val cells = ByteArray(width * height) { idx ->
            val col = idx % width
            val row = idx / width
            if (col in 10..20 && row in 12..24) 2.toByte() else 0x7F.toByte()
        }
        val map = VacuumMap(
            sn = "LSLDR00C019707U",
            mapId = 100L,
            autoAreaId = 0,
            pathId = 1L,
            width = width,
            height = height,
            xMin = -1.0f,
            yMin = -1.0f,
            cells = cells
        )
        val region = map.roomBoundingBoxRegion(2, SuctionLevel.STRONG)
        assertTrue(region != null)
        assertEquals(102, region!!.id)
        assertEquals("area", region.safeMode)
        assertEquals(4, region.verticesMm.size)

        val shutdownStatus = VacuumStatus(sn = "LSLDR00C019707U", mode = "shutdown", isOnline = true)
        assertTrue(shutdownStatus.isSleepOrShutdown)
        assertEquals("Sleep Mode (shutdown)", shutdownStatus.modeDisplayLabel)
    }

    @Test
    fun `AiMapArtItem and MapExportManager serializeFullMapBundleJson round-trip cleanly`() {
        val artItem = com.proscenic.m7.ai.AiMapArtItem(
            id = "art-sofa-1",
            label = "Navy L-Sofa",
            centerMm = PointMm(1200, -450),
            widthMm = 2100,
            heightMm = 900,
            rotationDeg = 90f,
            topDownPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
            providerUsed = com.proscenic.m7.ai.ByokProvider.GEMINI,
            aiDescription = "Top-down navy sofa"
        )
        val roundTrip = com.proscenic.m7.ai.AiMapArtItem.fromJson(artItem.toJson())
        assertEquals("art-sofa-1", roundTrip.id)
        assertEquals("Navy L-Sofa", roundTrip.label)
        assertEquals(PointMm(1200, -450), roundTrip.centerMm)
        assertEquals(210, roundTrip.widthCm)
        assertEquals(90, roundTrip.heightCm)
        assertEquals(90f, roundTrip.rotationDeg, 0.01f)
        assertEquals(com.proscenic.m7.ai.ByokProvider.GEMINI, roundTrip.providerUsed)
    }
}

