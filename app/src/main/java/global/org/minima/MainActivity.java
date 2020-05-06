package global.org.minima;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {

    public static MainActivity mMainLink;

    //The Help Button
    Button btnMini;

    //The IP of this device
    String mIP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        mIP = getIP();

        btnMini = findViewById(R.id.btn_minidapp);
        // btn open 127.0.0.1:21000 `Minidapp`
        btnMini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://"+mIP+":21000/"));
                startActivity(intent);
            }
        });

        TextView tv = findViewById(R.id.iptext_minidapp);
        tv.setText("\nConnect to Minima from your Desktop\n\nOpen a browser and go to\n\nhttp://" + mIP + ":21000/");

        //start Minima node Foreground Service
//        Intent intent = new Intent(this, NodeService.class);
//        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            startForegroundService(intent);
//        }
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
}
