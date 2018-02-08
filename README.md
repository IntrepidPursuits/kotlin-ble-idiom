# Kotlin Ble Idiom

This repo is a library that uses RxAndroidBLE and eases reading and writing information to and from
BLE devices. 

This library can provide these services:
- Provide communication to and from BLE devices.  
  Define a **BLE Service**, that a **BLE Device** provides, in an idiomatic way and be able to add properties 
  to that service that can easily be read and written using Rx Java. This includes notification,
  scanning, connection management, etc. The definition of a BLE Service allows for the provision of 
  transformers, transforming raw byte-arrays into proper Kotlin objects and vice-versa.
- Provide a framework for construction of Kotlin objects into raw byte-arrays and of destruction of
  Kotlin objects into raw byte-arrays. It allows mapping of byte-arrays 'directly' onto
  Kotlin objects with the correct packing of data and handling of bitmaps (ie values that take don't
  take up space that is a multiple of 8 bits). These objects are called **Struct Data** and they
  closely resemble `struct`-s from C and C++. 
- Provide utilities for writing unit-test dealing with BLE communication.
  Add instances of **BLE Test Helpers** to your unit tests and they will setup a mock BLE client and
  mock BLE server for you to use in your app's unit tests that need to deal with BLE communication.
  
# How to Use

## Start up and Configuration
This library uses dependency injection from Kodein to obtain instances of certain classes. If your
app is using this library, it **must** provide for these dependencies:

- **Context**  
  A *singleton* dependency that is the same as your app's application(-context).
- **BLEScanner**  
  A *singleton* dependency.
- **BLEIdiomDevice**  
  A *factory* dependency given a string that is the devices' mac-address.

The easiest way of providing these last two dependencies is by just importing the `PublicBleModule`, which
provides the default dependencies for instances of these two classes. A reason to not use the
`PublicBleModule` and provide your own version of these two dependencies is when you are writing
unit-tests.

In addition, the app can override these dependencies when it needs to. This library already has
proper default dependencies for them:

- **"DEBUG" boolean**  
  A *constant* or *singleton* boolean value. It is true when the build is a debug-build.
- **"BUILD_TYPE" string**  
  A *constant* or *singleton* string value containing the build-type of the build.
- **io.intrepid.bleidiom.log.Logger**  
  A *singleton* dependency that handles any logging of this library. 
- **Scheduler with tag TAG_EXECUTOR_SCHEDULER**  
  A *singleton* dependency of an Rx Scheduler that handles the syncing of shared connections.
  Your app should not override this one. Overriding should only be done for you app's unit-tests. 

The time to provide these required and overridden dependencies is when your app starts up and is
usually done on your app's `Application` creation. E.g you could add something like this in the `onCreate()`:
```
    kodein = ConfigurableKodein(true)
    
    val contextModule = Kodein.Module {
        bind<Context>() with singleton { appContext }
        ...
        ...
    }
    
    val bleIdiomModule = Kodein.Module(allowSilentOverride = true) {
        // Don't forget to first import the public Ble module
        // It'll provides the BleScanner an BleIdiomDevice instances.
        import(PublicBleModule)
    
        // Tell BleIdiom library the app's build-type if debugging is enabled or not.
        constant("DEBUG") with BuildConfig.DEBUG
        constant("BUILD_TYPE") with BuildConfig.BUILD_TYPE
    
        // Override the Logger of the BleIdiom lib.
        bind<Logger>() with singleton { MyAppLogger(if (instance("DEBUG")) LogLevel.VERBOSE else LogLevel.ASSERT) }
    }
    
    ... // more modules 
    
    val appModule = Kodein.Module {
        import(contextModule)
        ... // import more modules
        ...
    }
    // Add your own modules for your app
    kodein.addImport(appModule)
    // Add the module with the dependencies for this library; allow overriding default bindings.
    kodein.addImport(bleIdiomModule, allowOverride = true)
    
    // Call the `initBleIdiomModules` of this library to provide the correct dependencies
    initBleIdiomModules(kodein)
```

And then there is one more step. RxJava2 may cause your app to crash due to an Rx `UndeliverableException`.
This library would like to handle these undeliverable exceptions and have your app not crash. Your 
app should call `RxJavaPlugins.setErrorHandler` to catch Rx errors and give this library the 
opportunity to handle any BLE related `UndeliverableException` appropriately. 

Below is a snippet of code that you could use to make this happen, where the 
`UndeliverableBleExceptionHandler` is the error handler from this library:
```
fun configureRxErrorHandlers() {
    val errorHandlers = listOf<(Throwable) -> Boolean>(UndeliverableBleExceptionHandler)

    RxJavaPlugins.setErrorHandler { error ->
        val handled = errorHandlers.any { it(error) }
        if (!handled) {
            // Throw it so that it will crash the app.
            throw error
        }
    }
}
```

## Define and configure you BLE Service(s)

Take a look at the `io.intrepid.bleidiom.services.GenericAccess` class as an example of how to 
define a BLE Service.

A good rule of thumb is to call the `BleService<MyBleService> { configure { ... } }` during the 
`onCreate()` of your `Application`.

## How to obtain an instance of you BLE Service

You can obtain an instance either through BLE scanning or by getting one from a mac-address.
- Through scanning you can call `instance<BLEScanner>().scanForService<MyBleService>()` which will 
emit every `MyBleService` it finds around you.
- If you have a mac-address, you can get call `ServiceDeviceFactory.obtainClientDevice<MyBleService>(macAddress)`
and it will return a `MyBleService` with the given mac-address.

## How to use a BLE Service

Now that you have a BLE Service, you can easily read a write data and listen for incoming notifications.
Let's take the `GenericAccess` BLE Service as an example.

```
// Write new connection-params to the ble service
svcGA.preferredConnectionParams(ConnectionParams(100, 100, 100, 1))
    
// Read the device name
svcGA.deviceName().subscribe { name -> ... }
    
// Read the device name in an other way
svcGA[GenericAccess::deviceName].subscribe { name -> ... } 
    
// Double the connection-param's slave latency
svcGA[GenericAccess::preferredConnectionParams] = 
        svcGA[GenericAccess::preferredConnectionParams].map { 
            it.copy(slaveLatency = 2 * it.slaveLatency) 
        }         
```

When reading, writing or observing a BLE-characteristic property, such as `deviceName` 
or `preferredConnectionParams` in the example above, connections to the remote BLE device are 
established automatically and disconnected automatically as well. A disconnection *may* happen when
the subscriber to the write, read or observe operation un-subscribes or disposes of its 
subscription. Note that the form `service[ServiceClass::property] = something` will write and then
disconnect automatically without the need to dispose of any subscription.

A disconnection *may* happen when a Rx subscription is disposed, because other subscriptions may still
be active. Connections are shared and a connection will be closed only if no-one is subscribed to it
anymore. 

If you want to retain a long-lived connection to a service, call the service's `retainConnection()` 
method and `close()` it when you no longer want to retain that connection.

## Struct Data

Many times the data that is sent between your app and the remote BLE device does not have the form
of simple numbers or strings. Often the data is a small so-called structure that embeds any number
of strings, numbers, bit-masks, booleans, etc. To fit the data into the small MTU size of most BLE
devices, the data is packed tightly. That is where the **StructData** and **StructPacking** come in handy.

The `StructData` class will form the base-class for any piece of data your app would like to handle that 
needs to be packed inside a raw byte-array. `StructData` subclasses can be Kotlin `data class`-es or 
simple single-value classes such as `sealed class`-es with a single property.

A `data class` that is a sub-class of `StructData` mimics C/C++ `struct` definitions, which allow
packing the data if it is mapped onto a raw byte-array. In addition, the `StructData` class will
take care of the byte-order - endianness - of numbers.

E.g if you have this C/C++ struct
```
typedef struct {
   uint8_t is_available:1;
   uint8_t size:7;
   int32_t range;
   wchar name; 
} MyData_t
```
would be represented by a `StructData` subclass as follows
```
data class MyData(
    val isAvailable: Boolean,
    val size: Int,
    val range: Int,
    val name: String // UTF-8 string with terminating '\u00' character
) : StructData() {
    companion object: StructData.DataFactory() {
        override val packingInfo = array<Number>(
            UINT8(1),   // 1 bit for 'isAvailable'
            UINT8(7),   // 7 bits for unsigned 'size'
            UINT32,     // 32 bits for unsigned 'range'
            0           // Unknown length for the UTF8 string 'name'
        )
    }
}
```
See the Kotlin documentation on `StructData` and `StructPacking` for more information.

___
# Table of Contents

1. [Building](#building)
	1. [Onboarding](#onboarding)
	2. [Configurations](#configurations)
	3. [Running](#running)
2. [Testing](#testing)
3. [Merge Flow](#merge-flow)
	1. [Quirks](#quirks)
	2. [Known Bugs](#known-bugs)
	3. [Third Party Libraries](#third-party-libraries)
5. [History](#history)

___

# Building
## Onboarding
Install dependencies by building the project. It doesn't require any other special configuration to run.

## Configurations
There are no special configurations or build-flavors.

## Running
The app must be run on a device (as emulators don't support Bluetooth)
 
___

# Testing
There are no UI tests, but there are plenty of unit-tests.

# Merge Flow
- master: For now, there is only a master branch. 

## Quirks
It's a work in progress, there may be some :)

## Known Bugs
Please, tell us!

## Third Party Libraries
- [RxAndroidBLE 1.4.1](https://github.com/Polidea/RxAndroidBle)
- [RxJava 1.3.4](https://github.com/ReactiveX/RxJava)
- [RxJava 2.1.7](https://github.com/ReactiveX/RxJava)
- [RxAndroid 1.2.1](https://github.com/ReactiveX/RxAndroid)
- [RxKotlin 1.0.0](https://github.com/ReactiveX/RxKotlin)
- [RxAndroid 2.0.1](https://github.com/ReactiveX/RxAndroid)
- [RxKotlin 2.2.0](https://github.com/ReactiveX/RxKotlin)
- [RxJava Interop 0.10.9](https://github.com/akarnokd/RxJava2Interop)
- [Arrow 0.6.0](https://github.com/arrow-kt/arrow)
- [Bytes 0.4.6](https://github.com/patrickfav/bytes-java)
- [Kodein 4.1.0](https://github.com/SalomonBrys/Kodein)

# History
App is on GitHub only
- 2/6/2017: 0.0.1 Initial version, the code that's part of the KOH presentation "Idioms in Kotlin"
