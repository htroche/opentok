package com.tokbox.cordova;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.opentok.android.Connection;
import com.opentok.android.OpentokError;
import com.opentok.android.Publisher;
import com.opentok.android.PublisherKit;
import com.opentok.android.Session;
import com.opentok.android.Stream;
import com.opentok.android.Stream.StreamVideoType;
import com.opentok.android.Subscriber;
import com.opentok.android.SubscriberKit;


public class OpenTokAndroidPlugin extends CordovaPlugin implements
Session.SessionListener, Session.ConnectionListener, Session.SignalListener,
PublisherKit.PublisherListener, Session.StreamPropertiesListener{
    
    protected Session mSession;
    public static final String TAG = "OTPlugin";
    public boolean sessionConnected;
    public boolean publishCalled; // we need this because creating publisher before sessionConnected = crash
    public RunnablePublisher myPublisher;
    public HashMap<String, CallbackContext> myEventListeners;
    public HashMap<String, Connection> connectionCollection;
    public HashMap<String, Stream> streamCollection;
    public HashMap<String, RunnableSubscriber> subscriberCollection;
    
    private String apiKey; //= [command.arguments objectAtIndex:0];
    private String sessionId; // = [command.arguments objectAtIndex:1];
    private String tbToken; // = [command.arguments objectAtIndex:2];
    private int top; // = [[command.arguments objectAtIndex:3] intValue];
    private int left; // = [[command.arguments objectAtIndex:4] intValue];
    private int width; // = [[command.arguments objectAtIndex:5] intValue];
    private int height; // = [[command.arguments objectAtIndex:6] intValue];
    private int zIndex; // = [[command.arguments objectAtIndex:7] intValue];
    
    private OpenTokAndroidPlugin thisPlugin = this;
    
    static JSONObject viewList = new JSONObject();
    static CordovaInterface _cordova;
    static CordovaWebView _webView;
    
    
    public class RunnableUpdateViews implements Runnable{
        public JSONArray mProperty;
        public View mView;
        public ArrayList<RunnableUpdateViews> allStreamViews;
        public boolean isSubscriber = false;
        
        
        public class CustomComparator implements Comparator<RunnableUpdateViews> {
            @Override
            public int compare(RunnableUpdateViews object1, RunnableUpdateViews object2) {
                return object2.getZIndex() - object1.getZIndex();
            }
        }
        
        public void updateZIndices(){
            allStreamViews =  new ArrayList<RunnableUpdateViews>();
            for (Map.Entry<String, RunnableSubscriber> entry : subscriberCollection.entrySet() ) {
                allStreamViews.add( entry.getValue() );
            }
            if( myPublisher != null ){
                allStreamViews.add( myPublisher );
            }
            Collections.sort( allStreamViews, new CustomComparator() );
            
            for( RunnableUpdateViews viewContainer : allStreamViews ){
                ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                if (null != parent) {
                    parent.removeView( viewContainer.mView );
                    parent.addView(viewContainer.mView );
                }
            }
        }
        
        public int getZIndex(){
            return zIndex;
        }
        
        @SuppressLint("NewApi")
        @Override
        public void run() {
            try{
                
                
                if(isSubscriber)
                    mView.setY(top+height);
                else
                    mView.setY(top);
                mView.setX( left );
                ViewGroup.LayoutParams params = mView.getLayoutParams();
                params.height = height;
                params.width = width;
                mView.setLayoutParams(params);
                updateZIndices();
            }catch( Exception e ){
                Log.i(TAG, "error when trying to retrieve properties while resizing properties");
            }
        }
    }
    
    public class RunnablePublisher extends RunnableUpdateViews implements
    PublisherKit.PublisherListener, Publisher.CameraListener{
        //  property contains: [name, position.top, position.left, width, height, zIndex, publishAudio, publishVideo, cameraName] )
        public Publisher mPublisher;
        
        public RunnablePublisher( JSONArray args ){
            this.mProperty = args;
            
            // prevent dialog box from showing because it causes crash
            SharedPreferences prefs = cordova.getActivity().getApplicationContext().getSharedPreferences("permissions",
                                                                                                         Context.MODE_PRIVATE);
            Editor edit = prefs.edit();
            edit.clear();
            edit.putBoolean("opentok.publisher.accepted", true);
            edit.commit();
        }
        
        public void setPropertyFromArray( JSONArray args ){
            this.mProperty = args;
        }
        
        public void startPublishing(){
            cordova.getActivity().runOnUiThread( this );
        }
        
        
        public void destroyPublisher(){
            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                    parent.removeView( mView );
                    if(mPublisher != null) {
                        mPublisher.destroy();
                        mPublisher = null;
                    }
                }
            });
            
        }
        
        public void run() {
            Log.i(TAG, "view running on UIVIEW!!!");
            if( mPublisher == null ){
                ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                String publisherName = "Patient";
                
                mPublisher = new Publisher(cordova.getActivity().getApplicationContext(), publisherName);
                mPublisher.setCameraListener(this);
                mPublisher.setPublisherListener(this);
                try{
                    
                    mPublisher.setPublishVideo(true); // default is true
                    
                    mPublisher.setPublishAudio(true); // default is true
                    
                }catch( Exception e ){
                    Log.i(TAG, "error when trying to retrieve publish audio/video property");
                }
                this.mView = mPublisher.getView();
                frame.addView( this.mView );
                mSession = new Session(cordova.getActivity().getApplicationContext(), apiKey ,sessionId );
                //mSession.publish(mPublisher);
                mSession.setSessionListener(thisPlugin);
                mSession.setConnectionListener(thisPlugin);
                mSession.setSignalListener(thisPlugin);
                mSession.setStreamPropertiesListener(thisPlugin);
                mSession.connect(tbToken);
            }
            super.run();
        }
        
        // event listeners
        @Override
        public void onError(PublisherKit arg0, OpentokError arg1) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void onStreamCreated(PublisherKit arg0, Stream arg1) {
            Log.i(TAG, "publisher stream received");
            
            streamCollection.put(arg1.getStreamId(), arg1);
            triggerStreamCreated( arg1, "publisherEvents");
        }
        
        @Override
        public void onStreamDestroyed(PublisherKit arg0, Stream arg1) {
            streamCollection.remove( arg1.getStreamId() );
            triggerStreamDestroyed( arg1, "publisherEvents");
        }
        
        @Override
        public void onCameraChanged(Publisher arg0, int arg1) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void onCameraError(Publisher arg0, OpentokError arg1) {
            // TODO Auto-generated method stub
            
        }
        
    }
    
    public class RunnableSubscriber extends RunnableUpdateViews implements
    SubscriberKit.SubscriberListener, SubscriberKit.VideoListener{
        //  property contains: [stream.streamId, position.top, position.left, width, height, subscribeToVideo, zIndex] )
        public Subscriber mSubscriber;
        public Stream mStream;
        
        public RunnableSubscriber( JSONArray args, Stream stream ){
            this.mProperty = args;
            mStream = stream;
            isSubscriber = true;
            cordova.getActivity().runOnUiThread( this );
        }
        
        public void setPropertyFromArray( JSONArray args ){
            this.mProperty = args;
        }
        
        public void removeStreamView(){
            ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
            frame.removeView( this.mView );
            mSubscriber.destroy();
        }
        
        public void run() {
            if( mSubscriber == null ){
                logMessage("NEW SUBSCRIBER BEING CREATED");
                mSubscriber = new Subscriber(cordova.getActivity(), mStream);
                mSubscriber.setVideoListener(this);
                mSubscriber.setSubscriberListener(this);
                ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                this.mView = mSubscriber.getView();
                frame.addView( this.mView );
                mSession.subscribe(mSubscriber);
                Log.i(TAG, "subscriber view is added to parent view!");
            }
            super.run();
        }
        
        
        
        // listeners
        @Override
        public void onVideoDataReceived(SubscriberKit arg0) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void onConnected(SubscriberKit arg0) {
            // TODO Auto-generated method stub
            JSONObject eventData = new JSONObject();
            String streamId = arg0.getStream().getStreamId();
            try{
                eventData.put("streamId",streamId);
                triggerJSEvent("sessionEvents","subscribedToStream",eventData);
            } catch (JSONException e){
                Log.e(TAG, "JSONException"+e.getMessage());
            }
            Log.i(TAG, "subscriber"+streamId+" is connected");
            this.run();
        }
        
        @Override
        public void onDisconnected(SubscriberKit arg0) {
            // TODO Auto-generated method stub
        }
        
        @Override
        public void onError(SubscriberKit arg0, OpentokError arg1) {
            JSONObject eventData = new JSONObject();
            String streamId = arg0.getStream().getStreamId();
            int errorCode = arg1.getErrorCode().getErrorCode();
            try{
                eventData.put("errorCode", errorCode);
                eventData.put("streamId", streamId);
                triggerJSEvent("sessionEvents","subscribedToStream",eventData);
            } catch(JSONException e){
                Log.e(TAG, "JSONException"+e.getMessage());
            }
            Log.e(TAG, "subscriber exception: " + arg1.getMessage() + ", stream id: " + arg0.getStream().getStreamId() );
        }
        
        @Override
        public void onVideoDisableWarning(SubscriberKit arg0) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void onVideoDisableWarningLifted(SubscriberKit arg0) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void onVideoDisabled(SubscriberKit arg0, String arg1) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void onVideoEnabled(SubscriberKit arg0, String arg1) {
            // TODO Auto-generated method stub
            
        }
    }
    
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        _cordova = cordova;
        _webView = webView;
        Log.d(TAG, "Initialize Plugin");
        // By default, get a pointer to mainView and add mainView to the viewList as it always exists (hold cordova's view)
        if (!viewList.has("mainView")) {
            // Cordova view is not in the viewList so add it.
            try {
                viewList.put("mainView", webView);
                Log.d(TAG, "Found CordovaView ****** " + webView);
            } catch (JSONException e) {
                // Error handle (this should never happen!)
                Log.e(TAG, "Critical error. Failed to retrieve Cordova's view");
            }
        }
        
        // set OpenTok states
        publishCalled = false;
        sessionConnected = false;
        myEventListeners = new HashMap<String, CallbackContext>();
        connectionCollection = new HashMap<String, Connection>();
        streamCollection = new HashMap<String, Stream>();
        subscriberCollection = new HashMap<String, RunnableSubscriber>();
        
        super.initialize(cordova, webView);
    }
    
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.i( TAG, action );
        // TB Methods
        if( action.equals("initPublisher")){
            apiKey = args.getString(0);
            sessionId = args.getString(1);
            tbToken = args.getString(2);
            top = args.getInt(3);
            left = args.getInt(4);
            width = args.getInt(5);
            height = args.getInt(6);
            zIndex = args.getInt(7);
            myPublisher = new RunnablePublisher( args );
            myPublisher.startPublishing();
        }else if( action.equals( "destroyPublisher" )){
            if( myPublisher != null ){
                cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        myPublisher.destroyPublisher();
                        myPublisher = null;
                    }
                });
                
                callbackContext.success();
                return true;
            }
        }else if( action.equals( "initSession" )){
            Log.i( TAG, "created new session with data: "+args.toString());
            mSession = new Session(this.cordova.getActivity().getApplicationContext(), args.getString(0), args.getString(1));
            mSession.setSessionListener(this);
            mSession.setConnectionListener(this);
            mSession.setSignalListener(this);
            mSession.setStreamPropertiesListener(this);
            
            // publisher methods
        } else if( action.equals( "endCall" )){
            mSession.disconnect();
        }
        else if( action.equals( "setCameraPosition")){
            String cameraId = args.getString(0);
            if(myPublisher.mPublisher != null) {
                if (cameraId.equals("front")) {
                    myPublisher.mPublisher.setCameraId(1);
                } else if (cameraId.equals("back")) {
                    myPublisher.mPublisher.setCameraId(0);
                }
            }
        }else if( action.equals( "publishAudio") ){
            String val = args.getString(0);
            boolean publishAudio = true;
            if( val.equalsIgnoreCase("false") ){
                publishAudio = false;
            }
            Log.i(TAG, "setting publishAudio");
            myPublisher.mPublisher.setPublishAudio( publishAudio );
        }else if( action.equals( "publishVideo") ){
            String val = args.getString(0);
            boolean publishVideo = true;
            if( val.equalsIgnoreCase("false") ){
                publishVideo = false;
            }
            Log.i(TAG, "setting publishVideo");
            myPublisher.mPublisher.setPublishVideo( publishVideo );
            
            // session Methods
        }else if( action.equals( "addEvent" )){
            Log.i( TAG, "adding new event - " + args.getString(0));
            myEventListeners.put( args.getString(0), callbackContext);
        }else if( action.equals( "connect" )){
            Log.i( TAG, "connect command called");
            mSession.connect( args.getString(0));
        }else if( action.equals( "disconnect" )){
            mSession.disconnect();
        }else if( action.equals( "publish" )){
            if( sessionConnected ){
                Log.i( TAG, "publisher is publishing" );
                myPublisher.startPublishing();
            }
        }else if( action.equals( "signal" )){
            Connection c = connectionCollection.get(args.getString(2));
            if(c==null){
                mSession.sendSignal(args.getString(0), args.getString(1));
            }else{
                mSession.sendSignal(args.getString(0), args.getString(1), c);
            }
        }else if( action.equals( "unpublish" )){
            
        }else if( action.equals( "unsubscribe" )){
            
        }else if( action.equals( "subscribe" )){
            Log.i( TAG, "subscribe command called");
            Log.i( TAG, "subscribe data: " + args.toString() );
            Stream stream = streamCollection.get( args.getString(0) );
            RunnableSubscriber runsub = new RunnableSubscriber( args, stream );
            subscriberCollection.put(stream.getStreamId(), runsub);
        }else if( action.equals( "updateView" )){
            if( args.getString(0).equals("TBPublisher") && myPublisher != null && sessionConnected ){
                Log.i( TAG, "updating view for publisher" );
                //myPublisher.setPropertyFromArray(args);
                //cordova.getActivity().runOnUiThread(myPublisher);
            }else{
                RunnableSubscriber runsub = subscriberCollection.get( args.getString(0) );
                if( runsub != null ){
                    runsub.setPropertyFromArray( args );
                    cordova.getActivity().runOnUiThread(runsub);
                }
            }
        }else if( action.equals( "exceptionHandler" )){
            
        } else if(action.equals("destroySubPub")) {
            myPublisher.destroyPublisher();
            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    myPublisher.destroyPublisher();
                    mySubscriber.removeStreamView();
                }
            });
        }
        return true;
    }
    
    public void alertUser( String message){
        // 1. Instantiate an AlertDialog.Builder with its constructor
        AlertDialog.Builder builder = new AlertDialog.Builder( cordova.getActivity());
        builder.setMessage( message ).setTitle( "TokBox Message");
        AlertDialog dialog = builder.create();
    }
    
    
    // sessionListener
    @Override
    public void onConnected(Session arg0) {
        Log.i(TAG, "session connected, triggering sessionConnected Event. My Cid is: "+
              mSession.getConnection().getConnectionId()    );
        sessionConnected = true;
        
        connectionCollection.put(mSession.getConnection().getConnectionId(), mSession.getConnection());
        publish();
        JSONObject data = new JSONObject();
        /*try{
         data.put("status", "connected");
         JSONObject connection = createDataFromConnection( mSession.getConnection() );
         data.put("connection", connection);
         }catch (JSONException e) {}
         triggerJSEvent( "sessionEvents", "sessionConnected", data);*/
    }
    
    private void publish() {
        mSession.publish(myPublisher.mPublisher);
        //myPublisher.startPublishing();
    }
    
    RunnableSubscriber mySubscriber;
    private void subscribe(Stream stream) {
        mySubscriber = new RunnableSubscriber( null, stream );
        subscriberCollection.put(stream.getStreamId(), mySubscriber);
    }
    
    @Override
    public void onDisconnected(Session arg0) {
        sessionConnected = false;
        
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
                if( myPublisher != null ){
                    myPublisher.destroyPublisher();
                    myPublisher = null;
                }
                for (Map.Entry<String, RunnableSubscriber> entry : subscriberCollection.entrySet() ) {
                    if (null != parent) {
                        parent.removeView( entry.getValue().mView  );
                    }
                }
            }
        });
        
        // delete all data and prevent updateviews from drawing non existent things
        subscriberCollection.clear();
        connectionCollection.clear();
        streamCollection.clear();
        
        JSONObject data = new JSONObject();
        try{
            data.put("reason", "clientDisconnected");
        }catch (JSONException e) {}
        
        triggerJSEvent( "sessionEvents", "sessionDisconnected", data);
    }
    
    @Override
    public void onStreamDropped(Session arg0, Stream arg1) {
        Log.i(TAG, "session dropped stream");
        streamCollection.remove( arg1.getStreamId() );
        RunnableSubscriber subscriber = subscriberCollection.get( arg1.getStreamId() );
        if(subscriber != null){
            subscriber.removeStreamView();
            subscriberCollection.remove( arg1.getStreamId() );
        }
        
        triggerStreamDestroyed( arg1, "sessionEvents");
    }
    
    @Override
    public void onStreamReceived(Session arg0, Stream arg1) {
        Log.i(TAG, "stream received");
        
        subscribe(arg1);
        streamCollection.put(arg1.getStreamId(), arg1);
        triggerStreamCreated( arg1, "sessionEvents");
    }
    
    @Override
    public void onError(Session arg0, OpentokError arg1) {
        // TODO Auto-generated method stub
        Log.e(TAG, "session exception: " + arg1.getMessage());
        alertUser("session error "+arg1.getMessage());
    }
    
    // connectionListener
    public void onConnectionCreated(Session arg0, Connection arg1) {
        Log.i(TAG, "connectionCreated");
        
        connectionCollection.put(arg1.getConnectionId(), arg1);
        
        JSONObject data= new JSONObject();
        try{
            JSONObject connection = createDataFromConnection( arg1 );
            data.put("connection", connection);
        }catch (JSONException e) {}
        triggerJSEvent( "sessionEvents", "connectionCreated", data);
    }
    
    public void onConnectionDestroyed(Session arg0, Connection arg1) {Log.i(TAG, "connection dropped: " + arg1.getConnectionId());
        
        connectionCollection.remove( arg1.getConnectionId() );
        JSONObject data= new JSONObject();
        try{
            JSONObject connection = createDataFromConnection( arg1 );
            data.put("connection", connection);
        }catch (JSONException e) {}
        triggerJSEvent( "sessionEvents", "connectionDestroyed", data);
    }
    
    // signalListener
    public void onSignalReceived(Session arg0, String arg1, String arg2, Connection arg3) {
        JSONObject data= new JSONObject();
        Log.i(TAG, "signal type: " + arg1);
        Log.i(TAG, "signal data: " + arg2);
        try{
            data.put("type", arg1);
            data.put("data", arg2);
            if(arg3 != null){
                data.put("connectionId", arg3.getConnectionId());
            }
            triggerJSEvent( "sessionEvents", "signalReceived", data);
        }catch (JSONException e) {}
    }
    
    // streamPropertiesListener
    @Override
    public void onStreamHasAudioChanged(Session arg0, Stream arg1, boolean arg2) {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void onStreamHasVideoChanged(Session arg0, Stream arg1, boolean arg2) {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void onStreamVideoDimensionsChanged(Session arg0, Stream arg1,
                                               int arg2, int arg3) {
        // TODO Auto-generated method stub
        
    }
    
    
    // Helper Methods
    public void logMessage(String a){
        Log.i(TAG, a);
    }
    public boolean compareStrings(String a, String b){
        if(a != null && b != null && a.equalsIgnoreCase(b) ){
            return true;
        }
        return false;
    }
    public void triggerStreamDestroyed( Stream arg1, String eventType ){
        JSONObject data= new JSONObject();
        try{
            JSONObject stream = createDataFromStream( arg1 );
            data.put("stream", stream);
            triggerJSEvent( "sessionEvents", "streamDestroyed", data);
        }catch (JSONException e) {}
    }
    public void triggerStreamCreated( Stream arg1, String eventType ){
        JSONObject data= new JSONObject();
        try{
            JSONObject stream = createDataFromStream( arg1 );
            data.put("stream", stream);
            triggerJSEvent( eventType, "streamCreated", data);
        }catch (JSONException e) {}
        
        Log.i(TAG, "stream received done");
    }
    public JSONObject createDataFromConnection( Connection arg1 ){
        JSONObject connection = new JSONObject();
        
        try{
            connection.put("connectionId", arg1.getConnectionId());
            connection.put("creationTime", arg1.getCreationTime());
            connection.put("data", arg1.getData());
        }catch (JSONException e) {}
        return connection;
    }
    public JSONObject createDataFromStream( Stream arg1 ){
        JSONObject stream = new JSONObject();
        try{
            Connection connection = arg1.getConnection();
            if (connection != null) {
                stream.put("connectionId", connection.getConnectionId() );
            }
            stream.put("creationTime", arg1.getCreationTime() );
            stream.put("fps", -999);
            stream.put("hasAudio", arg1.hasAudio());
            stream.put("hasVideo", arg1.hasVideo());
            stream.put("name", arg1.getName());
            stream.put("streamId", arg1.getStreamId());
        }catch (Exception e) {}
        return stream;
    }
    public void triggerJSEvent(String event, String type, JSONObject data ){
        JSONObject message = new JSONObject();       
        
        try{
            message.put("eventType", type);
            message.put("data", data);
        }catch (JSONException e) {}
        
        PluginResult myResult = new PluginResult(PluginResult.Status.OK, message);
        myResult.setKeepCallback(true);
        //myEventListeners.get(event).sendPluginResult(myResult);
    }
    
    @Override
    public void onError(PublisherKit arg0, OpentokError arg1) {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void onStreamCreated(PublisherKit arg0, Stream stream) {
        subscribe(stream);
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void onStreamDestroyed(PublisherKit arg0, Stream arg1) {
        if(myPublisher != null){
            myPublisher.destroyPublisher();
            myPublisher = null;
        }
    }
    
    @Override
    public void onStreamVideoTypeChanged(Session arg0, Stream arg1,
                                         StreamVideoType arg2) {
        // TODO Auto-generated method stub
        
    }
}

