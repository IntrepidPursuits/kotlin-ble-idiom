/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.scan.ScanResult
import com.polidea.rxandroidble.scan.ScanSettings
import io.intrepid.bleidiom.module.LibKodein
import io.intrepid.bleidiom.util.toRx2
import io.reactivex.Observable
import io.reactivex.rxkotlin.ofType
import io.reactivex.rxkotlin.toObservable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.reflect.KClass

open class ServiceDeviceFactory {
    companion object {
        internal val registration = Registration

        @Suppress("UNCHECKED_CAST")
        inline fun <reified Svc : BleService<*>> obtainClientDevice(macAddress: String, autoConnect: Boolean = false): Svc =
                obtainClientDevice(getUUIDFromSvc(Svc::class), getBleIdiomDevice(macAddress), autoConnect)

        @Suppress("UNCHECKED_CAST")
        fun <Svc : BleService<*>> obtainClientDevice(uuid: UUID, bleDevice: RxBleDevice, autoConnect: Boolean = false): Svc =
                obtainClientDevice(uuid, LibKodein.with(bleDevice).instance<BleIdiomDevice>(), autoConnect)

        @Suppress("UNCHECKED_CAST")
        fun <Svc : BleService<*>> obtainClientDevice(uuid: UUID, bleDevice: BleIdiomDevice, autoConnect: Boolean = false): Svc =
                registration.createService(uuid)!!.apply {
                    device = bleDevice.apply { this.autoConnect = autoConnect }
                } as Svc
    }
}

fun ServiceDeviceFactory.Companion.getUUIDFromSvc(bleServiceClass: KClass<out BleService<*>>) =
        registration.getUUID(bleServiceClass).toUUID()!!

@Suppress("unused")
fun ServiceDeviceFactory.Companion.getBleIdiomDevice(macAddress: String) =
        LibKodein.with(macAddress).instance<BleIdiomDevice>()

/**
 * Scans for BLE devices that implement BLE Services that were
 * registered and configured by the [configureBleService].
 */
class BleScanner(private val bleClient: RxBleClient) : ServiceDeviceFactory() {
    /**
     * Starts scanning of BLE devices that implement the given [BleService].
     *
     * When a BLE device is scanned that implements the given [BleService], the returned
     * [Observable] will emit an instance of that [BleService].
     *
     * Subscribing to this Observable will start the scanning.
     * Un-subscribing from this Observable will stop the scanning.
     *
     * @param T The type of [BleService] that this scanner should emit.
     * @return an [Observable] of [BleService] instances.
     */
    inline
    fun <reified T : BleService<T>> scanForService() = scanForServices().ofType<T>()

    /**
     * Starts scanning of BLE devices that implement any registered and configured [BleService].

     * When a BLE device is scanned that implements a [BleService], the returned
     * [Observable] will emit an instance of that [BleService].
     *
     * Subscribing to this Observable will start the scanning.
     * Un-subscribing from this Observable will stop the scanning.
     *
     * @return an [Observable] of [BleService] instances.
     */
    fun scanForServices(): Observable<out BleService<*>> {
        val settings: ScanSettings = ScanSettings.Builder().build()
        return bleClient.scanBleDevices(settings)
                .toRx2()
                .flatMap { scanResult -> createScannedDevice(scanResult) }
    }

    private fun createScannedDevice(scanResult: ScanResult): Observable<BleService<*>> {
        val serviceUuids = scanResult.scanRecord.serviceUuids
        val scannedDevice = scanResult.bleDevice
        val ri = scanResult.rssi
        val scanBytes = scanResult.scanRecord.bytes

        return if (serviceUuids != null) serviceUuids.toObservable()
                .map { uuid -> uuid.uuid }
                .filter { uuid -> registration.hasService(uuid) }
                .map { uuid -> obtainClientDevice<BleService<*>>(uuid, scannedDevice) }
                .map { bleSvc -> bleSvc.apply { scanRecord = scanBytes; rssi = ri } }
        else Observable.empty()
    }
}

@Suppress("ArrayInDataClass")
data class ScanRecordInfo(
        val serviceUuids: List<UUID>?,
        val manufacturerData: Map<Int, ByteArray>?,
        val serviceData: Map<UUID, ByteArray>?,
        val advertiseFlags: Int,
        val txPowerLevel: Int,
        val localName: String?,
        val bytes: ByteArray,
        var parseError: Exception?
) {
    companion object {
        // The following data type values are assigned by Bluetooth SIG.
        // For more details refer to Bluetooth 4.1 specification, Volume 3, Part C, Section 18.
        private const val DATA_TYPE_FLAGS = 0x01
        private const val DATA_TYPE_SERVICE_UUIDS_16_BIT_PARTIAL = 0x02
        private const val DATA_TYPE_SERVICE_UUIDS_16_BIT_COMPLETE = 0x03
        private const val DATA_TYPE_SERVICE_UUIDS_32_BIT_PARTIAL = 0x04
        private const val DATA_TYPE_SERVICE_UUIDS_32_BIT_COMPLETE = 0x05
        private const val DATA_TYPE_SERVICE_UUIDS_128_BIT_PARTIAL = 0x06
        private const val DATA_TYPE_SERVICE_UUIDS_128_BIT_COMPLETE = 0x07
        private const val DATA_TYPE_LOCAL_NAME_SHORT = 0x08
        private const val DATA_TYPE_LOCAL_NAME_COMPLETE = 0x09
        private const val DATA_TYPE_TX_POWER_LEVEL = 0x0A
        private const val DATA_TYPE_SERVICE_DATA_16 = 0x16
        private const val DATA_TYPE_SERVICE_DATA_32 = 0x20
        private const val DATA_TYPE_SERVICE_DATA_128 = 0x21
        private const val DATA_TYPE_MANUFACTURER_SPECIFIC_DATA = 0xFF

        /** Length of bytes for 16 bit UUID  */
        private const val UUID_BYTES_16_BIT = 2
        /** Length of bytes for 32 bit UUID  */
        private const val UUID_BYTES_32_BIT = 4
        /** Length of bytes for 128 bit UUID  */
        private const val UUID_BYTES_128_BIT = 16

        private val BASE_UUID = UUID.fromString("00000000$LONG_UUID_BASE")

        /**
         * Method is copied from post lollipop [android.bluetooth.le.ScanRecord]
         */
        fun parseFromBytes(scanRecord: ByteArray?): ScanRecordInfo? {
            if (scanRecord == null) {
                return null
            }

            var currentPos = 0
            var advertiseFlag = -1
            val serviceUuids = mutableListOf<UUID>()
            var localName: String? = null
            var txPowerLevel = Integer.MIN_VALUE
            var exception: Exception? = null

            val manufacturerData = mutableMapOf<Int, ByteArray>()
            val serviceData = mutableMapOf<UUID, ByteArray>()

            try {
                while (currentPos < scanRecord.size) {
                    val length = scanRecord[currentPos++].toPositiveInt()
                    if (length == 0) break

                    val dataLength = length - 1
                    val fieldType = scanRecord[currentPos++].toPositiveInt()

                    when (fieldType) {
                        DATA_TYPE_FLAGS -> advertiseFlag = scanRecord[currentPos].toPositiveInt()

                        DATA_TYPE_SERVICE_UUIDS_16_BIT_PARTIAL, DATA_TYPE_SERVICE_UUIDS_16_BIT_COMPLETE ->
                            parseServiceUuid(scanRecord, currentPos, dataLength, UUID_BYTES_16_BIT, serviceUuids)

                        DATA_TYPE_SERVICE_UUIDS_32_BIT_PARTIAL, DATA_TYPE_SERVICE_UUIDS_32_BIT_COMPLETE ->
                            parseServiceUuid(scanRecord, currentPos, dataLength, UUID_BYTES_32_BIT, serviceUuids)

                        DATA_TYPE_SERVICE_UUIDS_128_BIT_PARTIAL, DATA_TYPE_SERVICE_UUIDS_128_BIT_COMPLETE ->
                            parseServiceUuid(scanRecord, currentPos, dataLength, UUID_BYTES_128_BIT, serviceUuids)

                        DATA_TYPE_LOCAL_NAME_SHORT, DATA_TYPE_LOCAL_NAME_COMPLETE ->
                            localName = String(extractBytes(scanRecord, currentPos, dataLength))

                        DATA_TYPE_TX_POWER_LEVEL -> txPowerLevel = scanRecord[currentPos].toInt()

                        DATA_TYPE_SERVICE_DATA_16, DATA_TYPE_SERVICE_DATA_32, DATA_TYPE_SERVICE_DATA_128 -> {
                            // The first x bytes of the service data are service data UUID in little
                            // endian. The rest bytes are service data.
                            val serviceUuidLength = when (fieldType) {
                                DATA_TYPE_SERVICE_DATA_32 -> UUID_BYTES_32_BIT
                                DATA_TYPE_SERVICE_DATA_128 -> UUID_BYTES_128_BIT
                                else -> UUID_BYTES_16_BIT
                            }
                            val serviceDataUuidBytes = extractBytes(scanRecord, currentPos, serviceUuidLength)
                            val serviceDataUuid = parseUuidFrom(serviceDataUuidBytes)
                            val serviceDataArray = extractBytes(scanRecord, currentPos + serviceUuidLength, dataLength - serviceUuidLength)
                            serviceData[serviceDataUuid] = serviceDataArray
                        }

                        DATA_TYPE_MANUFACTURER_SPECIFIC_DATA -> {
                            // The first two bytes of the manufacturer specific data are
                            // manufacturer ids in little endian.
                            val manufacturerId = (scanRecord[currentPos + 1].toPositiveInt() shl 8) + (scanRecord[currentPos].toPositiveInt())
                            val manufacturerDataBytes = extractBytes(scanRecord, currentPos + 2, dataLength - 2)
                            manufacturerData[manufacturerId] = manufacturerDataBytes
                        }
                    }

                    currentPos += dataLength
                }
            } catch (e: Exception) {
                // As the record is invalid, ignore all the parsed results for this packet
                // and return an empty record with raw scanRecord bytes in results
                exception = e
            }
            return ScanRecordInfo(if (serviceUuids.isEmpty()) null else serviceUuids, manufacturerData, serviceData, advertiseFlag, txPowerLevel, localName, scanRecord, exception)
        }

        private fun parseUuidFrom(uuidBytes: ByteArray): UUID {
            val length = uuidBytes.size
            if (length != UUID_BYTES_16_BIT && length != UUID_BYTES_32_BIT && length != UUID_BYTES_128_BIT) {
                throw IllegalArgumentException("uuidBytes length invalid - " + length)
            }

            // Construct a 128 bit UUID.
            if (length == UUID_BYTES_128_BIT) {
                val buf = ByteBuffer.wrap(uuidBytes).order(ByteOrder.LITTLE_ENDIAN)
                val msb = buf.getLong(8)
                val lsb = buf.getLong(0)
                return UUID(msb, lsb)
            }

            // For 16 bit and 32 bit UUID we need to convert them to 128 bit value.
            // 128_bit_value = uuid * 2^96 + BASE_UUID
            var shortUuid: Long
            if (length == UUID_BYTES_16_BIT) {
                shortUuid = (uuidBytes[0].toPositiveInt()).toLong()
                shortUuid += (uuidBytes[1].toPositiveInt() shl 8).toLong()
            } else {
                shortUuid = (uuidBytes[0].toPositiveInt()).toLong()
                shortUuid += (uuidBytes[1].toPositiveInt() shl 8).toLong()
                shortUuid += (uuidBytes[2].toPositiveInt() shl 16).toLong()
                shortUuid += (uuidBytes[3].toPositiveInt() shl 24).toLong()
            }
            val msb = BASE_UUID.mostSignificantBits + (shortUuid shl 32)
            val lsb = BASE_UUID.leastSignificantBits

            return UUID(msb, lsb)
        }

        // Parse service UUIDs.
        @Suppress("NAME_SHADOWING")
        private fun parseServiceUuid(scanRecord: ByteArray, currentPos: Int, dataLength: Int,
                                     uuidLength: Int, serviceUuids: MutableList<UUID>): Int {
            var currentPos = currentPos
            var dataLength = dataLength
            while (dataLength > 0) {
                val uuidBytes = extractBytes(scanRecord, currentPos, uuidLength)
                serviceUuids.add(parseUuidFrom(uuidBytes))
                dataLength -= uuidLength
                currentPos += uuidLength
            }
            return currentPos
        }

        private fun extractBytes(scanRecord: ByteArray, start: Int, length: Int) =
                scanRecord.sliceArray(start until start + length)
    }
}
