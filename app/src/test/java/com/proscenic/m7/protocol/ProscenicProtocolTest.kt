package com.proscenic.m7.protocol

import com.proscenic.m7.simulator.SimulatedVacuumEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProscenicProtocolTest {

    @Test
    fun `Lz4Codec round-trip compresses and decompresses 120x100 occupancy grid`() {
        val initialMap = SimulatedVacuumEngine.createInitialHouseMap()
        assertEquals(120, initialMap.width)
        assertEquals(100, initialMap.height)
        assertEquals(12_000, initialMap.cells.size)

        val b64 = Lz4Codec.encodeToBase64Lz4(initialMap.cells)
        val decompressed = Lz4Codec.decodeBase64Lz4(b64, expectedSize = 12_000)

        assertArrayEquals(initialMap.cells, decompressed)
    }

    @Test
    fun `parseMap20002 decodes Base64 LZ4 map and computes room centroids and cell math`() {
        val houseMap = SimulatedVacuumEngine.createInitialHouseMap()
        val payloadStr = SimulatedVacuumEngine.encodeMapAs20002Json(houseMap)
        val root = JSONObject(payloadStr)
        val dataObj = root.getJSONObject("data")

        val decoded = ProscenicProtocol.parseMap20002(dataObj)
        assertEquals(120, decoded.width)
        assertEquals(100, decoded.height)
        assertEquals(50, decoded.resolutionMm)
        assertEquals(PointMm(-2400, -1900), decoded.chargeHandlePos)
        assertTrue("Expected at least 4 segmented rooms", decoded.roomCentroidsMm.size >= 4)

        // Verify cell <-> world mm round-trip (`FUNC_MAP.md` §2.3)
        val sampleMm = PointMm(450, -750)
        val cell = decoded.worldMmToCell(sampleMm)
        val roundTripMm = decoded.cellToWorldMm(cell.col, cell.row)
        assertEquals(sampleMm.xMm, roundTripMm.xMm)
        assertEquals(sampleMm.yMm, roundTripMm.yMm)
    }

    @Test
    fun `MapMath snaps coordinates to 50mm grid and truncates region names to 31 UTF-8 bytes`() {
        assertEquals(150, MapMath.snapTo50Mm(147))
        assertEquals(-250, MapMath.snapTo50Mm(-230))

        // Verify 2-bit path tag stripping (`FUNC_MAP.md` §3.1)
        val taggedX = (1200 and 0xFFFFFFFC.toInt()) or 0x02
        val (cleanX, tagBits) = MapMath.stripPathTag(taggedX)
        assertEquals(1200, cleanX)
        assertEquals(2, tagBits)

        // Verify stack-overflow guard in task_manager FUN_0043da7c (max 31 UTF-8 bytes)
        val overlongName = "Kitchen_And_Dining_Room_Very_Long_Label_1234567890"
        val safe = MapMath.truncateRegionNameUtf8(overlongName)
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

        val f1 = ProscenicProtocol.unwrapFrameJson(frames[0])!!
        assertEquals(0, f1.getInt("encrypt"))
        val d1 = f1.getJSONObject("data")
        assertEquals(21023, d1.getInt("infoType"))
        val inner1 = d1.getJSONObject("data")

        val cleanIds = inner1.getJSONArray("cleanId")
        // Should include -3 (keep stored forbidden zones) plus 10, 11
        val idList = (0 until cleanIds.length()).map { cleanIds.getInt(it) }
        assertEquals(listOf(-3, 10, 11), idList)

        val segIds = inner1.getJSONArray("segmentId")
        val segList = (0 until segIds.length()).map { segIds.getInt(it) }
        assertEquals(listOf(1, 3), segList)

        // Frame 2 MUST be 21005 appointClean (never smartClean, which overwrites CleanArea)
        val f2 = ProscenicProtocol.unwrapFrameJson(frames[1])!!
        val d2 = f2.getJSONObject("data")
        assertEquals(21005, d2.getInt("infoType"))
        assertEquals("appointClean", d2.getJSONObject("data").getString("mode"))
    }

    @Test
    fun `buildSetSchedules splits overnight quiet hours across midnight and round-trips 21002`() {
        val schedules = SimulatedVacuumEngine.defaultSchedules()
        val quietHours = QuietHoursConfig(
            enabled = true,
            startHour = 23,
            startMinute = 0,
            endHour = 7,
            endMinute = 0
        )

        val frame = ProscenicProtocol.buildSetSchedules(schedules, quietHours)
        val unwrapped = ProscenicProtocol.unwrapFrameJson(frame)!!
        val dataObj = unwrapped.getJSONObject("data").getJSONObject("data")
        val items = dataObj.getJSONArray("items")

        // 2 active schedules + 2 split overnight quiet-hour entries (23:00->23:59 and 00:00->07:00)
        assertEquals(4, items.length())

        val parsedBack = ProscenicProtocol.parseSchedulesFrom21002(dataObj)
        assertEquals(2, parsedBack.size)
        assertEquals("09:00", parsedBack[0].formattedTime)
        assertEquals(setOf(1, 2, 3, 4, 5), parsedBack[0].repeatDays)
    }

    @Test
    fun `RehomeClient dryRun executes all 6 UDP 7913 steps without sending buggy bindOk`() = runBlocking {
        val client = RehomeClient()
        val result = client.executeRehome(
            serverHostIp = "192.168.1.50",
            httpPort = 8080,
            gatewayPort = 8081,
            wifiSsid = "HomeLAN",
            wifiPassword = "Password123",
            userId = "local_user",
            dryRun = true
        )

        assertTrue(result.success)
        assertEquals(6, result.steps.size)
        assertFalse(
            "Must never send buggy bindOk command (causes shell fork storm in FUN_00463c60)",
            result.steps.any { it.sentJson.contains("bindOk") }
        )
        assertTrue(result.steps[1].sentJson.contains("http://192.168.1.50:8080/"))
        assertTrue(result.steps[2].sentJson.contains("\"port\":8081"))
        assertTrue(result.steps[5].sentJson.contains("applyCfg"))
    }
}
