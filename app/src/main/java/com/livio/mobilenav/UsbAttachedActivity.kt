package com.livio.mobilenav

import android.content.Intent
import android.os.Bundle
import com.smartdevicelink.transport.USBAccessoryAttachmentActivity

class USBAttachedActivity : USBAccessoryAttachmentActivity() {

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
    }

}