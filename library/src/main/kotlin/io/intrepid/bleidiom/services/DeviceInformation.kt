package io.intrepid.bleidiom.services

import io.intrepid.bleidiom.BleCharValue
import io.intrepid.bleidiom.BleService
import io.intrepid.bleidiom.bleCharHandler

fun registerDeviceInformation() {
    BleService<DeviceInformation> {
        configure {
            uuid = "180a"

            read {
                data from "2a29" into ::manufacturerName
                data from "2a24" into ::modelName
                data from "2a27" into ::hardwareRev
                data from "2a26" into ::firmwareRev
                data from "2a28" into ::softwareRev
            }
        }
    }
}

class DeviceInformation : BleService<DeviceInformation>() {
    var manufacturerName: BleCharValue<String> by bleCharHandler()
    var modelName: BleCharValue<String> by bleCharHandler()
    var hardwareRev: BleCharValue<String> by bleCharHandler()
    var firmwareRev: BleCharValue<String> by bleCharHandler()
    var softwareRev: BleCharValue<String> by bleCharHandler()
}
