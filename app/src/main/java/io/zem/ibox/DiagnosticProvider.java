package io.zem.ibox;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants one selected diagnostic file, read-only, to the Android share recipient. */
public final class DiagnosticProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(!getContext().getPackageName().concat(".diagnostics").equals(uri.getAuthority())||uri.getPathSegments().size()!=1||name==null||!name.matches("Zem-iBox-[0-9a-f-]{36}\\.txt"))throw new FileNotFoundException("Invalid diagnostic file");
        File file=new File(new File(getContext().getCacheDir(),"shared-diagnostics"),name);
        if(!file.isFile())throw new FileNotFoundException("Diagnostic file expired");return file;
    }
    public String getType(Uri uri){return "text/plain";}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        try{File f=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor result=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?f.getName():OpenableColumns.SIZE.equals(columns[i])?f.length():null;result.addRow(values);return result;}catch(FileNotFoundException e){return null;}
    }
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("Read only");}
    public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("Read only");}
}
