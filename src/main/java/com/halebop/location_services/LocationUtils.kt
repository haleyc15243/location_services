package com.halebop.location_services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.shareIn
import kotlin.math.abs

class LocationProvider<T>(val provider: T) {
    //fun getLocation(): Location?
}

sealed interface LocationSensitivity {
    val diffThreshold: Double
    data object High: LocationSensitivity {
        override val diffThreshold: Double
            get() = 0.001
    }
    data object Medium: LocationSensitivity {
        override val diffThreshold: Double
            get() = 0.1
    }
    data object Low: LocationSensitivity {
        override val diffThreshold: Double
            get() = 1.0
    }
}

class LocationUtils(
    private val context: Context
) {
    private val locationProvider by lazy { locationProvider() }

    @SuppressLint("MissingPermission")
    fun requestLocationUpdates(locationSensitivity: LocationSensitivity): Flow<Location?> = callbackFlow {
        if (!context.hasLocationPermission()) {
            trySend(null)
            return@callbackFlow
        }

        when (val provider = locationProvider.provider) {
            is FusedLocationProviderClient -> {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10)
                    .setWaitForAccurateLocation(true)
                    .build()
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        trySend(result.lastLocation)
                    }

                    override fun onLocationAvailability(p0: LocationAvailability) {
                        //TODO
                    }
                }
                provider.requestLocationUpdates(
                    request,
                    callback,
                    Looper.getMainLooper()
                )
                awaitClose { provider.removeLocationUpdates(callback) }
            }
            is LocationManager -> {
                provider.requestLocationUpdates {
                    trySend(it)
                }
            }
            else -> {}
        }
    }
        .shareIn(CoroutineScope(Dispatchers.IO), SharingStarted.WhileSubscribed(100), 1)
        .filterNotNull()
        .distinctUntilChanged { old, new ->
            val absLatDiff = abs(old.latitude - new.latitude)
            val absLongDiff = abs(old.longitude - new.longitude)
            absLatDiff < locationSensitivity.diffThreshold|| absLongDiff < locationSensitivity.diffThreshold
        }

    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun getLastKnownLocation(): Location? {
        return when (val provider = locationProvider.provider) {
            is FusedLocationProviderClient -> {
                provider.lastLocation.result
            }
            is LocationManager -> {
                provider.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            else -> null
        }
    }

    private fun serviceAvailabilityResult() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    private fun locationProvider(): LocationProvider<Any> {
        return if (serviceAvailabilityResult() == ConnectionResult.SUCCESS) {
            LocationProvider(LocationServices.getFusedLocationProviderClient(context))
        } else {
            LocationProvider(getLocationManager(context))
        }
    }

    private fun getLocationManager(context: Context) = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun LocationManager.requestLocationUpdates(setLoc: (Location) -> Unit) {
        val hasGps = isProviderEnabled(LocationManager.GPS_PROVIDER)
        val hasNetwork = isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (hasGps) {
            requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000,
                0F,
                GpsLocationListener(setLoc)
            )
        }
        if (hasNetwork) {
            requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                5000,
                0F,
                NetworkLocationListener(setLoc)
            )
        }
    }

    private class GpsLocationListener(val setLoc: (Location) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) {
            setLoc(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    //------------------------------------------------------//
    private class NetworkLocationListener(val setLoc: (Location) -> Unit): LocationListener {
        override fun onLocationChanged(location: Location) {
            setLoc(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }


//    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
//    fun getLasKnownLocationFlow(): Flow<Location?> {
//        val stateFlow = MutableStateFlow<Location?>(null)
//        getLocationProvider()?.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)?.addOnSuccessListener {
//            stateFlow.value = it
//        }
//        return stateFlow
//    }
}

fun Context.hasLocationPermission() =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED