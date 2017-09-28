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

            defineBleServices()
        }
    }

    private lateinit var scanner: BleScanner
    private var batteryService: BatterijService? = null
    private var readBatterySub: Subscription? = null
    private var connectedServiceSub: Subscription? = null
    private var counter = 0

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val toolbar = findViewById(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        val fab = findViewById(R.id.fab) as FloatingActionButton
        fab.setOnClickListener { _ ->
            val battery = batteryService
            if (battery != null) {

                battery -= BatterijService::name
                battery[BatterijService::name] = Observable.fromCallable { "Counter reached ${++counter}" }
                        .repeatWhen { completed -> completed.delay(3, TimeUnit.SECONDS) }

                battery[BatterijService::name]
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

    private fun getPercentageObservable(scanner: BleScanner, connectedServiceObs: Observable<BatterijService>): Observable<Byte> {
        textView.alpha = 1f
        textView.text = "..."

        return connectedServiceObs
                .flatMap { battery -> battery[BatterijService::percentage] }
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
                .autoConnect(1, { replayConnection ->
                    connectedServiceSub = replayConnection
                })
    }

    private fun disconnect() {
        batteryService = null

        if (connectedServiceSub?.isUnsubscribed == false) {
            connectedServiceSub?.unsubscribe()
        }
        connectedServiceSub = null
    }
}
