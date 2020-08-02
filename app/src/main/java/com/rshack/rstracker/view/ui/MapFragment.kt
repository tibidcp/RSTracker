package com.rshack.rstracker.view.ui

import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.widget.Chronometer
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.rshack.rstracker.R
import com.rshack.rstracker.databinding.FragmentMapBinding
import com.rshack.rstracker.service.GpsService
import com.rshack.rstracker.viewmodel.MapViewModel
import java.text.SimpleDateFormat
import java.util.*

private const val PERMISSION_LOCATION = 1

class MapFragment : Fragment(), OnMapReadyCallback {

    companion object {
        fun newInstance() = MapFragment()
    }

    private lateinit var application: Application
    private val viewModel: MapViewModel by activityViewModels()
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var mMap: GoogleMap
    private lateinit var stopwatch: Chronometer
    private var isRunning = false

    private var trackDate: Long = 0

    private val points = mutableListOf<LatLng>()

    private val polyline = PolylineOptions()
        .width(5f)
        .color(Color.RED)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        application = requireNotNull(activity).application
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        stopwatch = binding.stopwatch

        binding.floatingButton.setOnClickListener {
            if (isRunning) {
                stopwatch.stop()
                stopwatch.base = SystemClock.elapsedRealtime()
                isRunning = false
                binding.floatingButton.setImageResource(R.drawable.ic_start)
                Log.i("how_to_get_millisec", "${SystemClock.elapsedRealtime() - stopwatch.base}")
                // TODO save in firebase
            } else {
                stopwatch.base = SystemClock.elapsedRealtime()
                stopwatch.start()
                binding.floatingButton.setImageResource(R.drawable.ic_stop)
                isRunning = true
            }
        }

        Log.d(GpsService.TAG, "onCreate")

        // Check GPS is enabled
        val lm =
            application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(
                application, "Please enable location services",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (isLocationPermissionGranted()) {
            Log.d(GpsService.TAG, "service started")
            startTrackerService()
        }

        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
        if (isLocationPermissionGranted()) {
            mMap.isMyLocationEnabled = true
            mMap.setOnMyLocationClickListener {location ->
                val latLng = LatLng(location.latitude, location.longitude)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 10f))
            }
        }

        subscribeToUpdates()
    }

    private fun startTrackerService() {
        trackDate = System.currentTimeMillis()
        val intent = Intent(application, GpsService()::class.java)
        intent.putExtra(GpsService.TRACK_DATE, trackDate)
        application.startService(intent)
    }

    private fun isLocationPermissionGranted(): Boolean {
        val permission = ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return if (permission == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_LOCATION
            )
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_LOCATION && grantResults.size == 1 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // Start the service when the permission is granted
            startTrackerService()
        }
    }

    private fun subscribeToUpdates() {
        val path = getString(R.string.firebase_path) + "/" + trackDate
        val ref =
            FirebaseDatabase.getInstance().getReference(path)
        ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(
                dataSnapshot: DataSnapshot,
                previousChildName: String?
            ) {
                addPoint(dataSnapshot)
            }

            override fun onChildChanged(
                dataSnapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onChildMoved(
                dataSnapshot: DataSnapshot,
                previousChildName: String?
            ) {
            }

            override fun onChildRemoved(dataSnapshot: DataSnapshot) {}
            override fun onCancelled(error: DatabaseError) {
                Log.d(
                    GpsService.TAG,
                    "Failed to read value.",
                    error.toException()
                )
            }
        })
    }

    private fun addPoint(dataSnapshot: DataSnapshot) {
        val value = dataSnapshot.value as HashMap<*, *>?
        if (value?.size!! > 2) {
            val lat = value["latitude"].toString().toDouble()
            val lng = value["longitude"].toString().toDouble()
            val location = LatLng(lat, lng)
            points.add(location)
            drawPolyline()
        }
    }

    private fun drawPolyline() {
        //clear map and polyline
        polyline.points.clear()
        mMap.clear()
        //add start and end markers
        mMap.addMarker(MarkerOptions().title("Start").position(points.first()))
        if (points.size > 1)
            mMap.addMarker(MarkerOptions().title("End").position(points.last()))
        //add polyline
        mMap.addPolyline(
            polyline.addAll(points)
        )
    }

    private fun polylineLength(): Float {
        if (points.size <= 1) {
            return 0f
        }
        return points.zipWithNext { a, b ->
            val results = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
            results[0]
        }.sum()
    }

}