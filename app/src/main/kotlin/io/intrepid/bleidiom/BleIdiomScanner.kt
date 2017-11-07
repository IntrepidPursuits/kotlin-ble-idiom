/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.scan.ScanResult
import com.polidea.rxandroidble.scan.ScanSettings
import io.reactivex.Observable
import io.reactivex.rxkotlin.ofType
import io.reactivex.rxkotlin.toObservable
import java.util.*

open class ServiceDeviceFactory {
    companion object {
        internal val registration = Registration

        @Suppress("UNCHECKED_CAST")
        fun <Svc : BleService<*>> createClientDevice(uuid: UUID, bleDevice: RxBleDevice): Svc =
                registration.createService(uuid)!!.apply { device = bleDevice } as Svc
    }
}

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
        return bleClient.scanBleDevices(settings).toRx2Observable().flatMap { scanResult -> createScannedDevice(scanResult) }
    }

    private fun createScannedDevice(scanResult: ScanResult): Observable<BleService<*>> {
        val serviceUuids = scanResult.scanRecord.serviceUuids
        val scannedDevice = scanResult.bleDevice

        @Suppress("IfThenToElvis")
        return if (serviceUuids != null) serviceUuids.toObservable()
                .map { uuid -> uuid.uuid }
                .filter { uuid -> registration.hasService(uuid) }
                .map { uuid -> createClientDevice<BleService<*>>(uuid, scannedDevice) }
        else Observable.empty()
    }
}
