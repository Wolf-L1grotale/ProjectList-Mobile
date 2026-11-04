package com.projectlist.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.projectlist.mobile.ui.AppRoot
import com.projectlist.mobile.ui.theme.PLTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PLTheme {
                AppRoot()
            }
        }
    }
}
