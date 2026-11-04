package io.zem.ibox;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Exposes only the verified update APK, read-only, to the Android installer. */
public final class UpdateProvider extends ContentProvider {
    static File apk(Context c){return new File(new File(c.getCacheDir(),"app-update"),"package.apk");}
    static Uri uri(Context c){return Uri.parse("content://"+c.getPackageName()+".updates/package.apk");}
    static Intent installer(Context c){Uri uri=uri(c);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Zem iBox update",uri));return intent;}
    public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{if(!uri(getContext()).equals(uri))throw new FileNotFoundException("Invalid update URI");File f=apk(getContext());if(!f.isFile())throw new FileNotFoundException("Update expired");return f;}
    public String getType(Uri uri){return "application/vnd.android.package-archive";}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File f=file(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(cols);Object[] row=new Object[cols.length];for(int i=0;i<cols.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?"Zem-iBox-update.apk":OpenableColumns.SIZE.equals(cols[i])?f.length():null;c.addRow(row);return c;}catch(FileNotFoundException e){return null;}}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String w,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String w,String[] a){throw new UnsupportedOperationException();}
}
