package com.health.openscale.core.facade

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.service.BleConnector
import com.health.openscale.ui.shared.SnackbarEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Date
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementFacadeBleSaveTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var settings: SettingsFacade
    private lateinit var facade: MeasurementFacade
    private lateinit var user: User
    private var weightId = 0

    @Before
    fun setUp() = runBlocking {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        settings = RoomTestSupport.settingsFacadeFor(
            scope, File(app.cacheDir, "ble-save-${System.nanoTime()}.preferences_pb")
        )
        settings.setSmartAssignmentEnabled(false)
        facade = RoomTestSupport.facadesFor(app, repo, settings).measurementFacade
        weightId = repo.getAllMeasurementTypes().first().first { it.key == MeasurementType.WEIGHT }.id
        val profile = User(
            name = "Test", birthDate = 0L, gender = GenderType.MALE, heightCm = 175f,
            activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false,
        )
        user = profile.copy(id = repo.insertUser(profile).toInt())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun measurement(timestamp: Long = 2_000L) = Measurement(userId = user.id, timestamp = timestamp)
    private fun values(weight: Float = 70f) = listOf(
        MeasurementValue(measurementId = 0, typeId = weightId, floatValue = weight)
    )

    @Test
    fun savedReadingReturnsPersistedId() = runBlocking {
        val result: Any? = facade.saveMeasurementFromBleDevice(measurement(), values())
        val saved = repo.getMeasurementsWithValuesForUser(user.id).first().single()
        assertThat(result).isEqualTo(saved.measurement.id)
        assertThat(saved.values.first { it.type.id == weightId }.value.floatValue).isEqualTo(70f)
    }

    @Test
    fun databaseFailureReachesCallerInsteadOfReportingSaved() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_measurement BEFORE INSERT ON Measurement BEGIN SELECT RAISE(ABORT, 'test write failure'); END"
        )
        val result = runCatching { facade.saveMeasurementFromBleDevice(measurement(), values()) }
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(repo.getMeasurementsWithValuesForUser(user.id).first()).isEmpty()
    }

    @Test
    fun duplicateIsSkippedAndLeavesOriginalValues() = runBlocking {
        facade.saveMeasurementFromBleDevice(measurement(), values())
        val result: Any? = facade.saveMeasurementFromBleDevice(measurement(), values(90f))
        assertThat(result).isNull()
        val saved = repo.getMeasurementsWithValuesForUser(user.id).first().single()
        assertThat(saved.values.first { it.type.id == weightId }.value.floatValue).isEqualTo(70f)
    }

    @Test
    fun smartAssignmentIgnoredReadingIsNotReportedSaved() = runBlocking {
        facade.saveMeasurementFromBleDevice(measurement(1_000L), values())
        settings.setSmartAssignmentEnabled(true)
        settings.setSmartAssignmentTolerancePercent(5)
        settings.setSmartAssignmentIgnoreOutsideTolerance(true)
        val result: Any? = facade.saveMeasurementFromBleDevice(measurement(), values(100f))
        assertThat(result).isNull()
        assertThat(repo.getMeasurementsWithValuesForUser(user.id).first()).hasSize(1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun connectorAnnouncesOnlySavedReadingsAndReportsWriteFailure() = runTest {
        val facades = RoomTestSupport.facadesFor(app, repo, settings)
        val connector = BleConnector(
            backgroundScope,
            ScaleFactory(app, settings, facades.measurementFacade, facades.userFacade),
            facades.measurementFacade,
            { ScaleUser(id = user.id) },
        )
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connector.snackbarEvents.collect { events += it }
        }
        runCurrent()
        // Exercise the existing event-to-database path without opening a Bluetooth connection.
        val receive = BleConnector::class.java.getDeclaredMethod(
            "saveMeasurementFromEvent", ScaleMeasurement::class.java, String::class.java
        ).apply { isAccessible = true }
        suspend fun reading(timestamp: Long, weight: Float) {
            val existingJobs = backgroundScope.coroutineContext[Job]!!.children.toSet()
            receive.invoke(connector, ScaleMeasurement(dateTime = Date(timestamp)).apply {
                this[MeasurementType.WEIGHT] = Kg(weight)
            }, "Test scale")
            backgroundScope.coroutineContext[Job]!!.children
                .filter { it !in existingJobs }.toList().forEach { it.join() }
            runCurrent()
        }
        suspend fun flushSavedMessage() {
            advanceTimeBy(701)
            runCurrent()
        }

        // A duplicate must not add another success notification or inflate the saved count.
        reading(1_000L, 70f)
        reading(1_000L, 90f)
        flushSavedMessage()
        assertThat(events.map { it.messageResId })
            .containsExactly(R.string.bluetooth_connector_measurement_saved)
        events.clear()

        settings.setSmartAssignmentEnabled(true)
        settings.setSmartAssignmentTolerancePercent(5)
        settings.setSmartAssignmentIgnoreOutsideTolerance(true)
        reading(2_000L, 100f)
        flushSavedMessage()
        assertThat(events).isEmpty()

        settings.setSmartAssignmentEnabled(false)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_measurement BEFORE INSERT ON Measurement BEGIN SELECT RAISE(ABORT, 'test write failure'); END"
        )
        reading(3_000L, 70f)
        flushSavedMessage()
        assertThat(events.map { it.messageResId })
            .containsExactly(R.string.bluetooth_connector_measurement_save_error)
        connector.close()
    }

    @Test
    fun assistedReadingAlsoReturnsPersistedId() = runBlocking {
        facade.saveMeasurementFromBleDevice(measurement(1_000L), values())
        facade.setPendingReferenceUserForBle(user)
        val result: Any? = facade.saveMeasurementFromBleDevice(measurement(), values(75f))
        val saved = repo.getMeasurementsWithValuesForUser(user.id).first()
            .single { it.measurement.timestamp == 2_000L }
        assertThat(result).isEqualTo(saved.measurement.id)
        assertThat(saved.values.first { it.type.id == weightId }.value.floatValue).isEqualTo(5f)
    }
}
