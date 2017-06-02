package com.pokescanner;

import android.content.res.ColorStateList;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.multidex.MultiDex;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.LatLng;
import com.pokescanner.events.ScanCircleEvent;
import com.pokescanner.helper.PokeDistanceSorter;
import com.pokescanner.helper.PokemonListLoader;
import com.pokescanner.loaders.MultiAccountLoader;
import com.pokescanner.objects.FilterItem;
import com.pokescanner.objects.Pokemons;
import com.pokescanner.objects.User;
import com.pokescanner.recycler.ListViewRecyclerAdapter;
import com.pokescanner.settings.Settings;
import com.pokescanner.utils.PermissionUtils;
import com.pokescanner.utils.SettingsUtil;
import com.pokescanner.utils.UiUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.OnLongClick;
import io.realm.Realm;
import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;

import static com.pokescanner.helper.Generation.makeHexScanMap;

/**
 * Created by Brian on 8/1/2016.
 */
public class ListViewActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    @BindView(R.id.recyclerListView)
    RecyclerView recyclerListView;
    @BindView(R.id.btnAutoScan)
    FloatingActionButton btnAutoScan;
    @BindView(R.id.progressBar)
    ProgressBar progressBar;

    ArrayList<Pokemons> pokemon = new ArrayList<>();
    ArrayList<Pokemons> pokemonRecycler = new ArrayList<>();
    LatLng cameraLocation;
    GoogleApiClient mGoogleApiClient;
    Subscription pokemonSubscriber;
    RecyclerView.Adapter mAdapter;

    Realm realm;
    Boolean autoScan = false;
    List<LatLng> scanMap;
    int pos = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        MultiDex.install(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_view);
        ButterKnife.bind(this);

        Bundle extras = getIntent().getExtras();

        if (extras != null) {
            if (extras.containsKey("cameraLocation")) {
                double location[] = extras.getDoubleArray("cameraLocation");
                if (location != null) {
                    cameraLocation = new LatLng(location[0], location[1]);
                }

            }
        }

        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }

        realm = Realm.getDefaultInstance();

        setupRecycler();

        refresh();
    }

    public void setupRecycler() {
        recyclerListView.setHasFixedSize(true);
        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(this);
        recyclerListView.setLayoutManager(mLayoutManager);

        mAdapter = new ListViewRecyclerAdapter(pokemonRecycler);

        recyclerListView.setAdapter(mAdapter);
    }

    public void refreshList() {
        //Create an arraylist to hold our new pokemon
        pokemon = new ArrayList<>(realm.copyFromRealm(realm.where(Pokemons.class).findAll()));
        //Clear our current list
        pokemonRecycler.clear();

        //Alright does our incomming list contain any pokemon that have expired?
        for (int i = 0 ;i < pokemon.size();i++) {
            //Put our pokemon inside an object
            Pokemons temp = pokemon.get(i);
            //Now we check has it expired
            if (!temp.isExpired()) {
                //If it has lets removed the pokemon
                realm.beginTransaction();
                realm.where(Pokemons.class).equalTo("encounterid",temp.getEncounterid()).findAll().deleteAllFromRealm();
                pokemon.remove(i);
                realm.commitTransaction();
            }
        }

        //Now we're getting our location
        LatLng latlng = getCurrentLocation();

        //On the offchance we don't have our location then lets get the camera location instead
        if (latlng == null) {
            latlng = cameraLocation;
        }

        //If the value isnt null then lets continue
        if (latlng != null) {
            Location location = new Location("");
            location.setLatitude(latlng.latitude);
            location.setLongitude(latlng.longitude);
            //Write distance to pokemons
            for (int i = 0; i < pokemon.size(); i++) {
                Pokemons pokemons = pokemon.get(i);
                //IF OUR POKEMANS IS FILTERED WE AINT SHOWIN HIM
                if (!PokemonListLoader.getFilteredList().contains(new FilterItem(pokemons.getNumber()))) {
                    //DO MATH
                    Location temp = new Location("");

                    temp.setLatitude(pokemons.getLatitude());
                    temp.setLongitude(pokemons.getLongitude());

                    double distance = location.distanceTo(temp);
                    pokemons.setDistance(distance);

                    //ADD OUR POKEMANS TO OUR OUT LIST
                    pokemonRecycler.add(pokemons);
                }
            }
        }

        Collections.sort(pokemonRecycler,new PokeDistanceSorter());
        mAdapter.notifyDataSetChanged();
    }

    public void scanMap() {
        if (autoScan) {
            if (!MultiAccountLoader.areThreadsRunning()) {
                pos = 1;
                progressBar.setProgress(0);
                //Get our scale for range
                int scale = Settings.get(this).getScanValue();
                int SERVER_REFRESH_RATE = Settings.get(this).getServerRefresh();
                //pull our GPS location
                LatLng scanPosition = getCurrentLocation();

                //On the offchance we don't have our location then lets get the camera location instead
                if (scanPosition == null) {
                    scanPosition = cameraLocation;
                }

                if (scanPosition != null) {
                    scanMap = makeHexScanMap(scanPosition, scale, 1, new ArrayList<LatLng>());
                    if (scanMap != null) {
                        showProgressbar(true);
                        //Pull our users from the realm
                        ArrayList<User> users = new ArrayList<>(realm.copyFromRealm(realm.where(User.class).findAll()));

                        MultiAccountLoader.setSleepTime(UiUtils.BASE_DELAY * SERVER_REFRESH_RATE);
                        //Set our map
                        MultiAccountLoader.setScanMap(scanMap);
                        //Set our users
                        MultiAccountLoader.setUsers(users);
                        //Begin our threads???
                        MultiAccountLoader.startThreads();
                    }
                }
            }
        }
    }

    public void refresh() {
        if (pokemonSubscriber != null)
            pokemonSubscriber.unsubscribe();

        //Using RX java we setup an interval to refresh the map
        pokemonSubscriber = Observable.interval(1, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
                .subscribe(new Action1<Long>() {
                    @Override
                    public void call(Long aLong) {
                        System.out.println("Refresh");
                        refreshList();
                        scanMap();
                    }
                });
    }

    @SuppressWarnings({"MissingPermission"})
    public LatLng getCurrentLocation() {
        if (PermissionUtils.doWeHaveGPSandLOC(this)) {
            if (mGoogleApiClient.isConnected()) {
                Location location = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
                if (location != null) {
                    return new LatLng(location.getLatitude(), location.getLongitude());
                }
                return null;
            }
            return null;
        }
        return null;
    }

    @OnLongClick(R.id.btnAutoScan)
    public boolean onClickButton() {
        SettingsUtil.searchRadiusDialog(this);
        return true;
    }

    @OnClick(R.id.btnAutoScan)
    public void onLongClick() {
        System.out.println("Click");
        if (autoScan) {
            btnAutoScan.setBackgroundTintList(ColorStateList.valueOf(ResourcesCompat.getColor(getResources(),R.color.colorAccent,null)));
            autoScan = false;
            MultiAccountLoader.cancelAllThreads();
            showProgressbar(false);
        }else {
            btnAutoScan.setBackgroundTintList(ColorStateList.valueOf(ResourcesCompat.getColor(getResources(),R.color.colorPrimary,null)));
            autoScan = true;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void createCircle(ScanCircleEvent event) {
        if (event.pos != null){
            float progress = (float) pos++ * 100 / scanMap.size();
            progressBar.setProgress((int) progress);
            if((int) progress == 100) {
                showProgressbar(false);
            }
        }
    }


    public void showProgressbar(boolean status) {
        if (status) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            progressBar.setVisibility(View.VISIBLE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            progressBar.setVisibility(View.INVISIBLE);

        }
    }
    @Override
    protected void onDestroy() {
        MultiAccountLoader.cancelAllThreads();
        pokemonSubscriber.unsubscribe();
        realm.close();
        super.onDestroy();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showProgressbar(false);
        EventBus.getDefault().register(this);
        realm = Realm.getDefaultInstance();
    }

    @Override
    protected void onPause() {
        EventBus.getDefault().unregister(this);
        super.onPause();
    }
}
