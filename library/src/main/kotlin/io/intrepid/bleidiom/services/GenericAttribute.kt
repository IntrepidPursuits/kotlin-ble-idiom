package io.intrepid.bleidiom.services

import io.intrepid.bleidiom.BleCharValue
import io.intrepid.bleidiom.BleService
import io.intrepid.bleidiom.bleCharHandler

fun registerGenericAttributes(){
    BleService<GenericAttribute> {
        configure {
            uuid = "1801"

            read {
                data from "2a05" into ::serviceChanged // 'indicate' only
            }
        }
    }
}

class GenericAttribute : BleService<GenericAttribute>() {
    val serviceChanged: BleCharValue<ByteArray> by bleCharHandler()
}
