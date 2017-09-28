package io.intrepid.bleidiom

import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.scan.ScanResult
import com.polidea.rxandroidble.scan.ScanSettings
import rx.Observable
import rx.lang.kotlin.ofType
import rx.lang.kotlin.toObservable

// Creates a scanner for one or more registered BleServices.
class BleScanner(private val bleClient: RxBleClient) {
    private val registration = BleServiceDSLImpl.Registration

    inline
    fun <reified T : BleService<T>> scanForService(): Observable<T> = scanForServices().ofType()

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
                .filter { uuid -> BleServiceDSLImpl.Registration.hasService(uuid) }
                .map { uuid -> createRegisteredServiceInstance(uuid, scannedDevice) }
        else Observable.empty()
    }

    private fun createRegisteredServiceInstance(uuid: String, bleDevice: RxBleDevice) =
            BleServiceDSLImpl.Registration.createService(uuid)!!.apply {
                device = bleDevice
            }
}
