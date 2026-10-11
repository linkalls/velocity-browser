package dev.velocity.browser.fixture

import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Debug-only synthetic SAF root; never included in Release/benchmark. */
class TransferDocumentsProvider : DocumentsProvider() {
    companion object { const val AUTHORITY="dev.velocity.browser.transfer-fixture" }
    private val directory get()=File(context!!.cacheDir,"transfer-fixture").apply { mkdirs() }
    override fun onCreate():Boolean {
        File(directory,"alpha.txt").writeText("fixture-alpha")
        File(directory,"beta.txt").writeText("fixture-beta")
        return true
    }
    private fun file(id:String):File { require(id!= "root" && id==File(id).name && id !in listOf(".",".."));return File(directory,id) }
    override fun queryRoots(projection:Array<out String>?):Cursor {
        val c=MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES,Root.COLUMN_AVAILABLE_BYTES))
        c.newRow().add(Root.COLUMN_ROOT_ID,"root").add(Root.COLUMN_DOCUMENT_ID,"root").add(Root.COLUMN_TITLE,"Velocity fixtures")
            .add(Root.COLUMN_FLAGS,Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE).add(Root.COLUMN_MIME_TYPES,"*/*").add(Root.COLUMN_AVAILABLE_BYTES,1024L*1024*1024)
        return c
    }
    private fun cursor(projection:Array<out String>?)=MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED))
    private fun add(c:MatrixCursor,id:String) {
        val f=if(id=="root")directory else file(id)
        c.newRow().add(Document.COLUMN_DOCUMENT_ID,id).add(Document.COLUMN_DISPLAY_NAME,if(id=="root")"Velocity fixtures" else id)
            .add(Document.COLUMN_MIME_TYPE,if(id=="root")Document.MIME_TYPE_DIR else "text/plain")
            .add(Document.COLUMN_FLAGS,if(id=="root")Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE)
            .add(Document.COLUMN_SIZE,f.length()).add(Document.COLUMN_LAST_MODIFIED,f.lastModified())
    }
    override fun queryDocument(documentId:String,projection:Array<out String>?)=cursor(projection).apply { add(this,documentId) }
    override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?)=cursor(projection).apply { require(parentDocumentId=="root");directory.listFiles()?.sortedBy { it.name }?.forEach { add(this,it.name) } }
    override fun openDocument(documentId:String,mode:String,signal:android.os.CancellationSignal?)=ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
        require(parentDocumentId=="root");val name=dev.velocity.browser.security.TransferPolicy.filename(displayName)
        var result=name;var n=1;while(File(directory,result).exists())result="${n++}-$name"
        File(directory,result).createNewFile();return result
    }
    override fun deleteDocument(documentId:String) { check(file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId:String,documentId:String)=parentDocumentId=="root" && documentId!="root"
}
