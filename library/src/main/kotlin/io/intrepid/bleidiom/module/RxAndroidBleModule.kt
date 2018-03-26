package io.intrepid.bleidiom.module

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.bindings.Scope
import com.github.salomonbrys.kodein.bindings.ScopeRegistry
import com.github.salomonbrys.kodein.factory
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.scopedSingleton
import com.github.salomonbrys.kodein.singleton
import com.github.salomonbrys.kodein.with
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import io.intrepid.bleidiom.BleIdiomDevice
import io.intrepid.bleidiom.BleScanner
import io.reactivex.Scheduler
import java.util.WeakHashMap

/**
 * Any app that wishes to use this ble-idiom library should import this module.
 * If necessary, bindings made in this module can be overridden (for testing and such).
 */
val PublicBleModule = Kodein.Module {
    // Provides the public interface to clients of this ble-idiom library
    import(BleModule)
    // Provides the private/internal interface to support the above BleModule.
    import(RxAndroidBleModule)
}

/**
 * This Kodein Module provides the *public* BleIdiom dependencies for libraries/apps that
 * use this BleIdiom library. It provides for this public classes: *
 * - [BleScanner] instances (singleton).
 * - [BleIdiomDevice] instances (weak-multiton given a [RxBleDevice])
 * - [Scheduler] instances for properly syncing shared connections
 *
 * Usage:
 *  * ```
 * val scanner: BleScanner = MyAppKodein.instance()
 * val device: BleIdiomDevice = MyAppKodein.with(macAddress).instance()
 * ```
 */
@Suppress("PropertyName")
private val BleModule = Kodein.Module {
    bind<BleScanner>() with singleton { BleScanner(instance()) }
    bind<BleIdiomDevice>() with factory { macAddress: String -> with(with(macAddress).instance<RxBleDevice>()).instance<BleIdiomDevice>() }
}

/**
 * This Kodein Module provides the *internal* RxAndroidBLE related classes and implementation.
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
private val RxAndroidBleModule = Kodein.Module {
    bind<RxBleClient>() with singleton { RxBleClient.create(instance()) }
    bind<RxBleDevice>() with factory { macAddress: String -> instance<RxBleClient>().getBleDevice(macAddress) }
    bind<BleIdiomDevice>() with scopedSingleton(DeviceScope) { device: RxBleDevice -> BleIdiomDevice(device) }
}

private object DeviceScope : Scope<RxBleDevice> {
    private val weakRegistry = WeakHashMap<RxBleDevice, ScopeRegistry>()

    override fun getRegistry(context: RxBleDevice) = synchronized(weakRegistry) {
        weakRegistry.getOrPut(context) { ScopeRegistry() }
    }!!
}
