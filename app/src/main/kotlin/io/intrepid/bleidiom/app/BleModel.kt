/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom.app

import io.intrepid.bleidiom.*

/**
 * Defines the BLE services that are used by this application.
 * For now, that is just the [BatterijService]
 */
fun defineBleServices() {
    // There are two ways of defining/configuring BLE services.

    // 1. Start DSL with the 'configureBleService' keyword (object).
    // The percentage (0x3a19) and name (0x3a00) are readable BLE characteristics
    // The name (0x3a00) is a writable BLE characteristic.
    configureBleService forClass BatterijService::class with {
        uuid = "790a4cfa-4058-4922-93f6-d9a5e168cc60"

        read {
            data from "3a19" into { ::percentage }
        }

        readAndWrite {
            data between { ::name } and "3a00"
            //data between BatterijService::name and "3a00"
        }
    }

    // 2. Start DSL with the BleService's companion object.
    // This is a User Data service.
    // https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.service.user_data.xml
    BleService<UserDataService> {
        configure {
            uuid = "181C"

            read {
                data from "0010" into ::count
            }
            readAndWrite {
                data between "2A8A" and ::firstName
                data between "2A90" and ::lastName
                data between "2A87" and ::emailAddress
            }
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

/**
 * Instances of this class represent a User Data Service of a BLE device.
 */
class UserDataService : BleService<UserDataService>() {
    var firstName: BleCharValue<String> by bleCharHandler()
    var lastName: BleCharValue<String> by bleCharHandler()
    var emailAddress: BleCharValue<String> by bleCharHandler()
    val count: BleCharValue<Int> by bleCharHandler()
}