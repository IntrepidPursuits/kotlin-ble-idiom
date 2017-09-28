package io.intrepid.bleidiom.app

import io.intrepid.bleidiom.*

fun defineBleServices() {
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

class BatterijService : BleService<BatterijService>() {
    val percentage: BleCharValue<Byte> by bleCharHandler()
    var name: BleCharValue<String> by bleCharHandler()

    val dummyJson = 5 asRawBleValue { """ {"count"=$this} """.toByteArray() }
}
