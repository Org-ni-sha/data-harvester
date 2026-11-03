package com.capstone.dataharvester

import com.capstone.dataharvester.sync.UploadPayload
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** The upload_history row: 1.6.4-1.6.6 left its dates empty and undercounted retries. */
class UploadHistoryTest {

    private val manila = TimeZone.getTimeZone("Asia/Manila")

    // 2026-11-03 13:05:09 in Manila (UTC+8) = 05:05:09 UTC
    private val uploadedAt = 1_793_682_309_000L

    private fun row(confirmed: Int = 157, attempted: Int = 157) = UploadPayload.uploadHistory(
        batchUid = "b", deviceId = "dev", uploadedAt = uploadedAt, attempted = attempted,
        confirmed = confirmed, payloadSizeBytes = 2048, appVersion = "1.6.7", zone = manila
    )

    @Test
    fun datesAreFilled_inThePhonesZone_with1_6_3sFormats() {
        val r = row()
        assertEquals(uploadedAt, r["uploaded_timestamp"])
        assertEquals("2026-11-03T13:05:09", r["datetime_str"])
        assertEquals("2026-11-03 13:05:09", r["datetime_stamp"])
    }

    @Test
    fun recordsUploaded_equalsRecordsConfirmed_evenOnARetry() {
        // The official data had records_uploaded 0 with 157 confirmed (all rows already there)
        val r = row(confirmed = 157, attempted = 157)
        assertEquals(157, r["records_uploaded"])
        assertEquals(157, r["records_confirmed"])
        val partial = row(confirmed = 90, attempted = 100)
        assertEquals(90, partial["records_uploaded"])
        assertEquals(100, partial["records_attempted"])
    }

    @Test
    fun keepsTheExistingCloudColumns() {
        assertEquals(
            listOf(
                "batch_uid", "device_id", "uploaded_timestamp", "datetime_str", "datetime_stamp",
                "records_uploaded", "records_attempted", "records_confirmed", "payload_size_bytes",
                "payload_size_kb", "app_version"
            ),
            row().keys.toList()
        )
        assertEquals(2.0, row()["payload_size_kb"])
    }
}
