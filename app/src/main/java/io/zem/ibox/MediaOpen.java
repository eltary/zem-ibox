package io.zem.ibox;

import android.content.*;
import android.net.Uri;

final class MediaOpen {
    static Intent vlc(Uri uri,String mime){return view(uri,mime).setPackage("org.videolan.vlc");}
    private static Intent view(Uri uri,String mime){Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Zem iBox media",uri));return intent;}
    static Intent folderChooser(Context context){
        Uri folder=android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/Zem iBox Drive");
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(folder,"resource/folder");
        Intent standard=new Intent(Intent.ACTION_VIEW).setDataAndType(folder,"vnd.android.document/directory");
        return Intent.createChooser(view,"Открыть папку записей").putExtra(Intent.EXTRA_INITIAL_INTENTS,new Intent[]{standard});
    }
    static Intent chooser(Uri uri,String mime){
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Zem iBox media",uri));
        return Intent.createChooser(view,mime!=null&&mime.startsWith("image/")?"Открыть фото в…":"Открыть видео в…");
    }
}
