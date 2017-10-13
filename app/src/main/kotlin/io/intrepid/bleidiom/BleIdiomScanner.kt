package io.intrepid.bleidiom

import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.scan.ScanResult
import com.polidea.rxandroidble.scan.ScanSettings
import rx.Observable
import rx.lang.kotlin.ofType
import rx.lang.kotlin.toObservable

/**
 * Scans for BLE devices that implement BLE Services that were
 * registered and configured by the [configureBleService].
 */
class BleScanner(private val bleClient: RxBleClient) {
    private val registration = BleServiceDSLImpl.Registration

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
    fun <reified T : BleService<T>> scanForService(): Observable<T> = scanForServices().ofType()

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
        return bleClient.scanBleDevices(settings).flatMap { scanResult -> createScannedDevice(scanResult) }
    }

    private fun createScannedDevice(scanResult: ScanResult): Observable<BleService<*>> {
        val serviceUuids = scanResult.scanRecord.serviceUuids
        val scannedDevice = scanResult.bleDevice

        @Suppress("IfThenToElvis")
        return if (serviceUuids != null) serviceUuids.toObservable()
                .map { uuid -> uuid.uuid.toString() }
                .filter { uuid -> registration.hasService(uuid) }
                .map { uuid -> createRegisteredServiceInstance(uuid, scannedDevice) }
        else Observable.empty()
    }

    private fun createRegisteredServiceInstance(uuid: String, bleDevice: RxBleDevice) =
            registration.createService(uuid)!!.apply {
                device = bleDevice
            }
}
