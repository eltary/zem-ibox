package io.zem.ibox;

import android.content.*;
import android.net.Uri;

final class MediaOpen {
    static Intent vlc(Uri uri,String mime){return view(uri,mime).setPackage("org.videolan.vlc");}
    private static Intent view(Uri uri,String mime){Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Zem iBox media",uri));return intent;}
    static Intent folderChooser(Context context){
        Uri tree=StorageLocation.tree(context);boolean custom=tree!=null;
        Uri folder=custom?android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree)):android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/Zem iBox Drive");
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(folder,"resource/folder");
        Intent standard=new Intent(Intent.ACTION_VIEW).setDataAndType(folder,"vnd.android.document/directory");
        if(custom){for(Intent option:new Intent[]{view,standard}){option.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);option.setClipData(ClipData.newRawUri("Папка записей",folder));}}
        java.util.ArrayList<Intent> alternatives=new java.util.ArrayList<>();alternatives.add(standard);
        if(!custom){
            Intent downloads=new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS),samsung=new Intent(downloads).setPackage("com.sec.android.app.myfiles");
            if(samsung.resolveActivity(context.getPackageManager())!=null)alternatives.add(samsung);
            else{Intent myFiles=context.getPackageManager().getLaunchIntentForPackage("com.sec.android.app.myfiles");alternatives.add(myFiles!=null?myFiles:downloads);}
        }
        return Intent.createChooser(view,"Открыть папку записей").putExtra(Intent.EXTRA_INITIAL_INTENTS,alternatives.toArray(new Intent[0]));
    }

    static Intent chooser(Uri uri,String mime){
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Zem iBox media",uri));
        return Intent.createChooser(view,mime!=null&&mime.startsWith("image/")?"Открыть фото в…":"Открыть видео в…");
    }
}
