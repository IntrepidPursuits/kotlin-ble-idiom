package io.intrepid.bleidiom.services

import io.intrepid.bleidiom.BleCharValue
import io.intrepid.bleidiom.BleService
import io.intrepid.bleidiom.bleCharHandler

fun registerGenericAccess() {
    BleService<GenericAccess> {
        configure {
            uuid = "1800"

            read {
                data from "2a00" into ::deviceName
                data from "2a01" into ::appearance
            }

            readAndWrite {
                data between "2a04" and ::preferredConnectionParams
            }
        }
    }
}

class GenericAccess : BleService<GenericAccess>() {
    var deviceName: BleCharValue<String> by bleCharHandler()
    var appearance: BleCharValue<Short> by bleCharHandler()
    var preferredConnectionParams: BleCharValue<ConnectionParams> by bleCharHandler {
        fromByteArray = { StructData.construct(ConnectionParams::class, it) }
        toByteArray = { it.deconstruct() }
    }
}

/**
 * Mimics the C/C++
 *
 *      typedef struct {
 *          uint16_t minConnectionInterval;
 *          uint16_t maxConnectionInterval;
 *          uint16_t slaveLatency;
 *          uint16_t timeoutMultiplier;
 *      } ConnectionParams_t
 */
data class ConnectionParams(
        val minConnectionInterval: Int,
        val maxConnectionInterval: Int,
        val slaveLatency: Int,
        val timeoutMultiplier: Int
) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(UINT16, UINT16, UINT16, UINT16)
    }
}


fun test(svcGA: GenericAccess) {
svcGA[GenericAccess::preferredConnectionParams] =
        svcGA[GenericAccess::preferredConnectionParams].map {
            it.copy(slaveLatency = 2 * it.slaveLatency)
        }
}