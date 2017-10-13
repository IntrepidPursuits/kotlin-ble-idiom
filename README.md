# Idioms in Kotlin, a DSL for BLE

This repo contains the code that was the subject of a "Kotlin Office Hours" presentation called "Idioms in Kotlin".

This presentation can be found here:  
   https://drive.google.com/open?id=1co09CMF7g9rhmrqiw73YSpixThP644grxo3iBrNB1FA
 
The *MainActivity* is nothing more that a dummy shell around a simple example that shows the definition and the usage
 of a DSL that defines Bluetooth Low Energy (BLE) services.
   
The rest of the code defines the definition and the usage of the BLE-DSL.
   
The best place to start looking at this code is 
- Look at the DSL defining a BLE Service first:   
  *./app/src/main/kotlin/io/intrepid/bleidiom/app/BleModel.kt*  
- Then look at the **interface** *defining* this DSL:  
  *./app/src/main/kotlin/io/intrepid/bleidiom/BleIdiom.kt*   
- And look at the *usage* of this DSL:  
  *./app/src/main/kotlin/io/intrepid/bleidiom/app/MainActivity.kt*
  
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
4. [Architecture](#architecture)
	1. [Third Party Libraries](#third-party-libraries)
5. [History](#history)

___

# Building
## Onboarding
Install dependencies by building the project. It doesn't require any other special configuration to run.

## Configurations
There are no special configurations or build-flavors.

## Running
The app must be run on a device (as emulators don't support Bluetooth) and you should install the Node.js-module **Bleno**.

https://github.com/sandeepmistry/bleno 

   Clone the above repo.  
   Follow the installation instructions.   
   **Replace** contents of directory *./examples/battery-service/* with the scripts from the *bleno-demo-scripts*.  
   Run them by executing *node main.js* in your terminal/shell.   
   Be sure to turn on your PCs/Macs Bluetooth!  
  
___

# Testing
There are no UI tests currently.

# Merge Flow
- master: For now, there is only a master branch. 

## Quirks
It's a work in progress, there may be some :)

## Known Bugs
Please, tell us!
___

# Architecture
## Third Party Libraries
- [RxAndroidBLE 1.4.1](https://github.com/Polidea/RxAndroidBle)
- [RxJava 1.3.1](https://github.com/ReactiveX/RxJava)
- [RxAndroid 1.2.1](https://github.com/ReactiveX/RxAndroid)
- [RxKotlin 1.0.0](https://github.com/ReactiveX/RxKotlin)

___

# Info
## Kotlin Office Hours Meetup
- https://www.meetup.com/kotlin-office-hours/events/243635842 
- https://www.meetup.com/kotlin-office-hours

# History
App is on GitHub only
- 2/6/2017: 0.0.1 Initial version, the code that's part of the KOH presentation "Idioms in Kotlin"