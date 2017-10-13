package io.intrepid.bleidiom.app

import android.content.Context
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.util.Log
import android.widget.TextView
import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.internal.RxBleLog
import io.intrepid.bleidiom.BleScanner
import io.intrepid.bleidiom.R
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.lang.kotlin.subscribeBy
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        init {
            RxBleLog.setLogLevel(RxBleLog.VERBOSE)
            RxBleLog.setLogger { level, tag, msg -> Log.println(level, tag, msg) }

            // Upon loading of this MainActivity class, define and register the BatterijService.
            defineBleServices()
        }
    }

    private lateinit var scanner: BleScanner
    private var batteryService: BatterijService? = null
    private var readBatterySub: Subscription? = null
    private var connectedServiceSub: Subscription? = null
    private var counter = 0

    private lateinit var textView: TextView

    /**
     * Example of how to read a Byte BLE characteristic.
     * @param batterijService from which to get the name.
     */
    private fun getBatterijPercentage(batterijService: BatterijService) =
        batterijService[BatterijService::percentage]

    /**
     * Example of how to read a String BLE characteristic.
     * @param batterijService from which to get the percentage.
     */
    private fun getBatterijName(batterijService: BatterijService) =
        batterijService[BatterijService::name]

    /**
     * Example of how to write a value to a BLE characteristic
     * @param batterijService whose name will be set.
     * @param name The name to be set.
     */
    private fun setBatterijName(batterijService: BatterijService, name: String) {
        batterijService[BatterijService::name] = name
    }

    /**
     * Example of how to emit (stream) values to a BLE characteristic.
     * @param batterijService whose name will be set each time the Observable emits a new value.
     * @param nameStream The Observable that will 'stream' values for the service's name.
     */
    private fun streamBatterijName(batterijService: BatterijService, nameStream: Observable<String>) {
        batterijService[BatterijService::name] = nameStream
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val toolbar = findViewById(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        val fab = findViewById(R.id.fab) as FloatingActionButton
        fab.setOnClickListener { _ ->
            val battery = batteryService
            if (battery != null) {

                setBatterijName(battery, "Counter reached ${++counter}")

                getBatterijName(battery)
                        .take(1)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeBy({ name -> toolbar.title = name })
            }
        }

        textView = findViewById(android.R.id.text1) as TextView

        scanner = createScanner(this)
    }

    override fun onResume() {
        super.onResume()

        readBatterySub = getPercentageObservable(scanner, connect(scanner))
                .subscribeBy(
                        { value ->
                            textView.animate().alpha(0f).setDuration(100).withEndAction {
                                textView.text = "$value%"
                                textView.animate().alpha(1f).setDuration(233)
                            }
                        })
    }

    override fun onPause() {
        if (readBatterySub?.isUnsubscribed == false) {
            readBatterySub?.unsubscribe()
        }
        readBatterySub = null

        disconnect()

        super.onPause()
    }

    private fun getPercentageObservable(scanner: BleScanner, connectedServiceObs: Observable<BatterijService>):
            Observable<Byte> {
        textView.alpha = 1f
        textView.text = "..."

        return connectedServiceObs
                .flatMap { battery -> getBatterijPercentage(battery) }
                .take(1)
                .repeatWhen { completed -> completed.delay(2, TimeUnit.SECONDS) }
                .observeOn(AndroidSchedulers.mainThread())
                .onErrorResumeNext { getPercentageObservable(scanner, connect(scanner)) }
    }

    private fun createScanner(context: Context) = BleScanner(RxBleClient.create(context))

    private fun connect(scanner: BleScanner): Observable<BatterijService> {
        disconnect()

        return scanner.scanForService<BatterijService>()
                .take(1)
                .flatMap { service -> service.connect() }
                .doOnNext { service -> batteryService = service }
                .replay()
                .autoConnect(1) { replayConnection ->
                    connectedServiceSub = replayConnection
                }
    }

    private fun disconnect() {
        batteryService = null

        if (connectedServiceSub?.isUnsubscribed == false) {
            connectedServiceSub?.unsubscribe()
        }
        connectedServiceSub = null
    }
}
