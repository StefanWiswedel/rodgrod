package dk.rodgrod.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(RodgrodPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
