package com.phoneagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Invisible activity: lets the Quick Settings tile close the shade, then opens the summon menu. */
class SummonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Quick.summon()) {
            Toast.makeText(this, "Turn on Quick access in Iris first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}

/** The Iris tile in the swipe-down Quick Settings panel. */
class IrisTile : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        startActivityAndCollapse(Intent(this, SummonActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
