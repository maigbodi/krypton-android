package co.krypt.kryptonite;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import co.krypt.kryptonite.exception.CryptoException;
import co.krypt.kryptonite.exception.TransportException;
import co.krypt.kryptonite.protocol.MeResponse;
import co.krypt.kryptonite.protocol.PairingQR;
import co.krypt.kryptonite.protocol.Profile;
import co.krypt.kryptonite.protocol.Request;
import co.krypt.kryptonite.protocol.Response;
import co.krypt.kryptonite.transport.SQSTransport;

/**
 * Created by Kevin King on 12/2/16.
 * Copyright 2016. KryptCo, Inc.
 */

public class PairScanner {
    private LinkedBlockingQueue<byte[]> frames;
    private AtomicBoolean stopped;
    private final Context context;
    private final int height;
    private final int width;

    private final BarcodeDetector detector;

    private static final String TAG = "PairScanner";

    public PairScanner(Context context, int height, int width) {
        this.context = context;
        this.height = height;
        this.width = width;
        frames = new LinkedBlockingQueue<>(1);
        stopped = new AtomicBoolean(false);
        detector = new BarcodeDetector.Builder(context)
                .setBarcodeFormats(Barcode.QR_CODE)
                .build();
        if(!detector.isOperational()){
            Log.e(TAG, "Could not set up the detector!");
            return;
        }

        final PairScanner self = this;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (stopped.get()) {
                        return;
                    }
                    try {
                        byte[] data = frames.poll(500, TimeUnit.MILLISECONDS);
                        if (data != null) {

                            Bitmap bitmap = Bitmap.createBitmap(self.width, self.height, Bitmap.Config.ARGB_8888);
                            Allocation bmData = renderScriptNV21ToRGBA888(
                                    self.context,
                                    self.width,
                                    self.height,
                                    data);
                            bmData.copyTo(bitmap);
                            Frame frame = new Frame.Builder().setBitmap(bitmap).build();
                            SparseArray<Barcode> barcodes = detector.detect(frame);
                            for (int i = 0; i < barcodes.size(); i++) {
                                Barcode barcode = barcodes.valueAt(i);
                                if (barcode == null) {
                                    return;
                                }
                                if (barcode.rawValue != null) {
                                    PairingQR pairingQR = PairingQR.parseJson(barcode.rawValue);
                                    Log.i(TAG, "found pairingQR: " + Base64.encodeToString(pairingQR.workstationPublicKey, Base64.DEFAULT));
                                    Pairing pairing = Pairing.generate(pairingQR);
                                    byte[] wrappedKey = pairing.wrapKey(pairing.symmetricSecretKey);
                                    NetworkMessage wrappedKeyMessage = new NetworkMessage(NetworkMessage.Header.WRAPPED_KEY, wrappedKey);
                                    SQSTransport.sendMessage(pairing, wrappedKeyMessage);
                                    List<byte[]> messages = SQSTransport.receiveMessages(pairing);
                                    for (byte[] incoming : messages) {
                                        try {
                                            NetworkMessage message = NetworkMessage.parse(incoming);
                                            switch (message.header) {
                                                case CIPHERTEXT:
                                                    byte[] json = pairing.unseal(message.message);
                                                    Log.i(TAG, "got JSON " + new String(json, "UTF-8"));
                                                    Request request = JSON.fromJson(json, Request.class);
                                                    Response response = Response.with(request);
                                                    if (request.meRequest != null) {
                                                        response.meResponse = new MeResponse(
                                                                new Profile(
                                                                        "kevin@krypt.co",
                                                                        KeyManager.loadOrGenerateKeyPair(KeyManager.MY_RSA_KEY_TAG).publicKeySSHWireFormat()));
                                                    }
                                                    byte[] responseJson = JSON.toJson(response).getBytes();
                                                    byte[] sealed = pairing.seal(responseJson);
                                                    SQSTransport.sendMessage(pairing, new NetworkMessage(NetworkMessage.Header.CIPHERTEXT, sealed));
                                                    break;
                                                case WRAPPED_KEY:
                                                    break;
                                            }
                                        } catch (TransportException e) {
                                            Log.e(TAG, e.getMessage());
                                        } catch (UnrecoverableEntryException e) {
                                            e.printStackTrace();
                                        } catch (NoSuchAlgorithmException e) {
                                            e.printStackTrace();
                                        } catch (CertificateException e) {
                                            e.printStackTrace();
                                        } catch (InvalidKeyException e) {
                                            e.printStackTrace();
                                        } catch (InvalidAlgorithmParameterException e) {
                                            e.printStackTrace();
                                        } catch (KeyStoreException e) {
                                            e.printStackTrace();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        } catch (NoSuchProviderException e) {
                                            e.printStackTrace();
                                        } catch (JsonSyntaxException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (CryptoException e) {
                        e.printStackTrace();
                    } catch (TransportException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    public void stop() {
        stopped.set(true);
    }

    public void pushFrame(byte[] frame) {
        frames.offer(frame);
    }

    private static Allocation renderScriptNV21ToRGBA888(Context context, int width, int height, byte[] nv21) {
        RenderScript rs = RenderScript.create(context);
        ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));

        Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(nv21.length);
        Allocation in = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);

        Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height);
        Allocation out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);

        in.copyFrom(nv21);

        yuvToRgbIntrinsic.setInput(in);
        yuvToRgbIntrinsic.forEach(out);
        return out;
    }
}
