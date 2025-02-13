package global.org.minima;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.minima.GlobalParams;
import org.minima.system.brains.ConsensusHandler;
import org.minima.utils.MinimaLogger;
import org.minima.utils.messages.Message;
import org.minima.utils.messages.MessageListener;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import com.minima.service.MinimaService;

public class MinimaActivity extends AppCompatActivity implements ServiceConnection, MessageListener {

    //The Help Button
    Button btnMini;
    Button btnLogs;

    //The IP Text..
    TextView mTextIP;

    //The IP of this device
    String mIP;

    //The Service..
    MinimaService mMinima = null;

    boolean mSynced = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

//        MinimaLogger.log("Activity : onCreate");

        setContentView(global.org.minima.R.layout.activity_main);

        //The Button to open the local browser
        btnMini = findViewById(global.org.minima.R.id.btn_minidapp);
        btnMini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:9004/"));
                startActivity(intent);
            }
        });
        btnMini.setVisibility(View.GONE);

        btnLogs= findViewById(global.org.minima.R.id.btn_logs);
        btnLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MinimaActivity.this, MinimaLogs.class);
                startActivity(intent);
            }
        });

        //The TEXT that shows the current IP
        mTextIP = findViewById(R.id.iptext_minidapp);
        mTextIP.setText("\nSynchronising.. please wait..");

        //start Minima node Foreground Service
        Intent minimaintent = new Intent(getBaseContext(), MinimaService.class);
        startForegroundService(minimaintent);

        bindService(minimaintent, this, Context.BIND_AUTO_CREATE);
    }

//    @Override
//    protected void onStart() {
//        super.onStart();
//        MinimaLogger.log("Activity : onStart");
//    }
//
//    @Override
//    protected void onStop() {
//        super.onStop();
//        MinimaLogger.log("Activity : onStop");
//    }

    @Override
    protected void onResume() {
        super.onResume();
        //MinimaLogger.log("Activity : onResume");
        setIPText();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //MinimaLogger.log("Activity : onDestroy");

        //Remove the message listener.. don;t want to clog it up..
        if(mMinima != null){
            try{
                mMinima.getMinima().getServer().getConsensusHandler().removeListener(this);
            }catch(Exception exc){

            }

            //Clean Unbind
            unbindService(this);
        }
    }

    public void setIPText() {
        //Set the IP - it may change..
        if (mSynced) {
            mIP = getIP();
            mTextIP.setText("\nConnect to Minima v"+ GlobalParams.MINIMA_VERSION +" from your Desktop\n\n" +
                    "Open a browser and go to\n\n" +
                    "http://" + mIP + ":9004/");
        }
    }

    public String getIP(){
        String mHost = "127.0.0.1";
        boolean found = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (!found && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while(!found && addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String ip   = addr.getHostAddress();
                    String name = iface.getDisplayName();

                    //Only get the IPv4
                    if(!ip.contains(":")) {
                        mHost = ip;
                        if(name.startsWith("wl")) {
                            found = true;
                            break;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.out.println("Minima Network IP : "+e);
        }

        return mHost;
    }

    public void setPostSyncDetails(){
        Runnable uiupdate = new Runnable() {
            @Override
            public void run() {
                mSynced = true;
                btnMini.setVisibility(View.VISIBLE);
                setIPText();
                MinimaLogger.log("ACTIVITY : GET STARTED");
            }
        };

        //Update..
        runOnUiThread(uiupdate);
    }

    public void setPercentInitial(final String zMessage){
        if(!mSynced) {
            Runnable uiupdate = new Runnable() {
                @Override
                public void run() {
                    mTextIP.setText("\n" + zMessage);
                }
            };

            //Update..
            runOnUiThread(uiupdate);
        }
    }

//    @Override
//    public void onBackPressed() {
//        moveTaskToBack(true);
//    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        //MinimaLogger.log("CONNECTED TO SERVICE");
        MinimaService.MyBinder binder = (MinimaService.MyBinder)iBinder;
        mMinima = binder.getService();

        Thread addlistener = new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    while(mMinima.getMinima() == null){Thread.sleep(250);}
                    while(mMinima.getMinima().getServer() == null){Thread.sleep(250);}
                    while(mMinima.getMinima().getServer().getConsensusHandler() == null){Thread.sleep(250);}

                    //ready..
                    if(mMinima.getMinima().getServer().getConsensusHandler().isInitialSyncComplete()){
                        MinimaLogger.log("ACTIVITY : INITIAL SYNC COMPLETE ON STARTUP..");
                        setPostSyncDetails();
                    }else{
                        MinimaLogger.log("ACTIVITY : LISTENING FOR SYNC COMPLETE..");
                        //Listen for messages..
                        mMinima.getMinima().getServer().getConsensusHandler().addListener(MinimaActivity.this);
                    }
                }catch(Exception exc){

                }
            }
        });
        addlistener.start();

    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        MinimaLogger.log("DISCONNECTED TO SERVICE");
    }

    @Override
    public void processMessage(Message zMessage) {
        if (zMessage.isMessageType(ConsensusHandler.CONSENSUS_NOTIFY_INITIALSYNC)) {
            MinimaLogger.log("ACTIVITY SYNC COMPLETE : " + zMessage);

            setPercentInitial("Almost Done.. Checking MiniDAPPs");

//            if(!mSynced) {
//                Thread rr = new Thread(new Runnable() {
//                    @Override
//                    public void run() {
//                        try {
//                            setPercentInitial("Almost Done.. !");
//                            Thread.sleep(3000);
//
//                            setPercentInitial("Almost Done.. 3");
//                            Thread.sleep(3000);
//
//                            setPercentInitial("Almost Done.. 2");
//                            Thread.sleep(3000);
//
//                            setPercentInitial("Almost Done.. 1");
//                            Thread.sleep(3000);
//
//                        } catch (Exception exc) {
//                        }
//
//                        //Set the correct view..
//                        setPostSyncDetails();
//                    }
//                });
//                rr.start();
//            }

        }else if (zMessage.isMessageType(ConsensusHandler.CONSENSUS_NOTIFY_INITIALPERC)) {
            setPercentInitial(zMessage.getString("info"));

        }else if (zMessage.isMessageType(ConsensusHandler.CONSENSUS_NOTIFY_DAPP_RELOAD)) {
            MinimaLogger.log("ACTIVITY : DAPPS LOADED");
            //Set the correct view..
            setPostSyncDetails();

        }else if (zMessage.isMessageType(ConsensusHandler.CONSENSUS_NOTIFY_DAPP_INSTALLED)) {
            String dapp = zMessage.getString("name");

            MinimaLogger.log("ACTIVITY : DAPP INSTALLED "+dapp);

            setPercentInitial("MiniDAPP installed : "+dapp);

        }else if (zMessage.isMessageType(ConsensusHandler.CONSENSUS_NOTIFY_LOG)) {
            String log = zMessage.getString("msg");
        }
    }
}
