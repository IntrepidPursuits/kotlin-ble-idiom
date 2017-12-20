package io.intrepid.bleidiom.module

import com.github.salomonbrys.kodein.*
import com.github.salomonbrys.kodein.bindings.Scope
import com.github.salomonbrys.kodein.bindings.ScopeRegistry
import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleDevice
import io.intrepid.bleidiom.BleIdiomDevice
import io.intrepid.bleidiom.BleScanner
import java.util.*

/**
 * This *public* Kodein Module provides the *internal* RxAndroidBLE related classes and implementation.
 * An app/library that uses this BleIdiom library must import this module or a mock version of it, as
 * long as it provides for instances of these classes:
 * - [RxBleClient] instances (singleton).
 * - [RxBleDevice] instances (factory given a mac-address)
 * - [BleIdiomDevice] instances scoped to a given [RxBleDevice]
 *
 * The app/library that imports this module does it only on the behalf of this BleIdiom library.
 * It should never need to actually obtain instances of RxBleClient and RxBleDevice, since these
 * are BleIdiom implementation details.
 */
@Suppress("PropertyName")
internal val RxAndroidBleModule = Kodein.Module {
    bind<RxBleClient>() with singleton { RxBleClient.create(instance()) }
    bind<RxBleDevice>() with factory { macAddress: String -> instance<RxBleClient>().getBleDevice(macAddress) }
    bind<BleIdiomDevice>() with scopedSingleton(DeviceScope) { device: RxBleDevice -> BleIdiomDevice(device) }
}

/**
 * This *internal* Kodein Module provides the *public* BleIdiom dependencies for libraries/apps that
 * use this BleIdiom library. It provides for this public classes: *
 * - [BleScanner] instances (singleton).
 * - [BleIdiomDevice] instances (weak-multiton given a [RxBleDevice])
 *
 * Usage:
 *  * ```
 * val scanner: BleScanner = MyAppKodein.instance()
 * val device: BleIdiomDevice = MyAppKodein.with(macAddress).instance()
 * ```
 */
@Suppress("PropertyName")
internal val BleModule = Kodein.Module {
    bind<BleScanner>() with singleton { BleScanner(instance()) }
    bind<BleIdiomDevice>() with factory { macAddress: String -> with(with(macAddress).instance<RxBleDevice>()).instance<BleIdiomDevice>() }
}

private object DeviceScope : Scope<RxBleDevice> {
    private val weakRegistry = WeakHashMap<RxBleDevice, ScopeRegistry>()

    override fun getRegistry(context: RxBleDevice) = synchronized(weakRegistry) {
        weakRegistry.getOrPut(context) { ScopeRegistry() }
    }!!
}