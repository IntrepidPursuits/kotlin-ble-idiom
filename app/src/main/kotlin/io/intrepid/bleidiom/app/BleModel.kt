package io.intrepid.bleidiom.app

import io.intrepid.bleidiom.*

/**
 * Defines the BLE services that are used by this application.
 * For now, that is just the [BatterijService]
 */
fun defineBleServices() {

    // The percentage (0x3a19) and name (0x3a00) are readable BLE characteristics
    // The name (0x3a00) is a writable BLE characteristic.
    configureBleService forClass BatterijService::class with {
        uuid = "790a4cfa-4058-4922-93f6-d9a5e168cc60"

        read {
            data from "3a19" into BatterijService::percentage
            data into BatterijService::name from "3a00"
        }

        write {
            data into "3a00" from BatterijService::name
        }
    }
}

/**
 * Instances of this class represent a Batterij-Service of a BLE device.
 */
class BatterijService : BleService<BatterijService>() {
    /**
     * Represents the read-only BLE characteristic that represents the battery-level percentage.
     *
     * This is an example where [BleCharHandlerDSL.fromByteArray] and [BleCharHandlerDSL.toByteArray] are explicitly set.
     */
    val percentage: BleCharValue<Byte> by bleCharHandler {
        fromByteArray = { toByteArrayNumber(it) }
        toByteArray = { toNumberByteArray(it) }
    }

    /**
     * Represents the read/write BLE characteristic that represents the service's name.
     *
     * This is an example where the [BleCharHandlerDSL] implicitly sets the correct value for
     * [BleCharHandlerDSL.fromByteArray] and [BleCharHandlerDSL.toByteArray] based on the [BleCharValue]'s **Val** type.
     */
    var name: BleCharValue<String> by bleCharHandler()
}
