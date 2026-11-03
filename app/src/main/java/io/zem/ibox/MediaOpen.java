package io.zem.ibox;

import android.content.*;
import android.net.Uri;

final class MediaOpen {
    static Intent vlc(Uri uri,String mime){return view(uri,mime).setPackage("org.videolan.vlc");}
    private static Intent view(Uri uri,String mime){Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Zem iBox media",uri));return intent;}
    static Intent chooser(Uri uri,String mime){
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Zem iBox media",uri));
        return Intent.createChooser(view,mime!=null&&mime.startsWith("image/")?"Открыть фото в…":"Открыть видео в…");
    }
}
