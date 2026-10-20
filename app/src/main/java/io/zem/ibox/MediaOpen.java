package io.zem.ibox;

import android.content.*;
import android.net.Uri;

final class MediaOpen {
    static Intent chooser(Uri uri,String mime){
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Zem iBox Drive media",uri));
        return Intent.createChooser(view,mime!=null&&mime.startsWith("image/")?"Открыть фото в…":"Открыть видео в…");
    }
}
