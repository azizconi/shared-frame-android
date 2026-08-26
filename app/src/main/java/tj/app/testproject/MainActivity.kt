package tj.app.testproject

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import tj.app.testproject.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.openViewsDemo.setOnClickListener { startActivity(Intent(this, ViewsDemoActivity::class.java)) }
        binding.openComposeDemo.setOnClickListener { startActivity(Intent(this, ComposeDemoActivity::class.java)) }
    }
}
