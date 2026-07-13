package dev.thor.rombutler.receive

import dev.thor.rombutler.data.files.IncomingFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded LAN server with a protected per-session path. Browser uploads use
 * resumable chunks which remain hidden until the expected byte count is
 * complete; the legacy multipart endpoint remains available for compatibility.
 */
class ReceiveServer(
    port: Int,
    private val targetDir: File,
    private val sessionToken: String,
    private val onFileReceived: (String) -> Unit,
) : NanoHTTPD(port) {

    private val pagePath = "/$sessionToken/"
    private val legacyUploadPath = "/$sessionToken/upload"
    private val chunkPath = "/$sessionToken/chunk"
    private val statusPath = "/$sessionToken/status"
    private val healthPath = "/$sessionToken/health"
    private val uploadLocks = ConcurrentHashMap<String, Any>()

    init {
        cleanupStaleUploads()
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == pagePath ->
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", UPLOAD_PAGE)

        session.method == Method.GET && session.uri == statusPath -> handleStatus(session)
        session.method == Method.GET && session.uri == healthPath -> healthResponse()
        session.method == Method.POST && session.uri == chunkPath -> handleChunk(session)
        session.method == Method.POST && session.uri == legacyUploadPath -> handleLegacyUpload(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val uploadId = session.parameters["id"]?.firstOrNull()
        if (!isValidUploadId(uploadId)) return errorResponse(Response.Status.BAD_REQUEST, "Invalid upload ID")
        val partial = partialFile(uploadId!!)
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("offset", partial.takeIf(File::isFile)?.length() ?: 0L)
                .put("resumable", true),
        )
    }

    private fun healthResponse(): Response = jsonResponse(
        Response.Status.OK,
        JSONObject()
            .put("ready", targetDir.isDirectory || targetDir.mkdirs())
            .put("freeBytes", targetDir.usableSpace)
            .put("port", listeningPort)
            .put("resumable", true),
    )

    private fun handleChunk(session: IHTTPSession): Response {
        val uploadId = session.header(HEADER_UPLOAD_ID)
        val expectedOffset = session.header(HEADER_OFFSET)?.toLongOrNull()
        val totalBytes = session.header(HEADER_TOTAL_SIZE)?.toLongOrNull()
        if (!isValidUploadId(uploadId) || expectedOffset == null || totalBytes == null ||
            expectedOffset < 0L || totalBytes <= 0L || expectedOffset > totalBytes
        ) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid upload metadata")
        }

        return try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val (parameter, tempPath) = body.entries.firstOrNull()
                ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing chunk")
            val originalName = session.parameters[parameter]?.firstOrNull()
                ?: return errorResponse(Response.Status.BAD_REQUEST, "Missing file name")
            val safeName = IncomingFile.sanitizeName(originalName)
                ?: return errorResponse(Response.Status.BAD_REQUEST, "Invalid file name")
            val chunk = File(tempPath)
            if (!chunk.isFile || chunk.length() <= 0L || chunk.length() > MAX_CHUNK_BYTES) {
                return errorResponse(Response.Status.BAD_REQUEST, "Invalid chunk size")
            }

            val lock = uploadLocks.getOrPut(uploadId!!) { Any() }
            synchronized(lock) {
                appendChunk(uploadId, safeName, expectedOffset, totalBytes, chunk)
            }
        } catch (e: Exception) {
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "Upload failed")
        }
    }

    private fun appendChunk(
        uploadId: String,
        safeName: String,
        expectedOffset: Long,
        totalBytes: Long,
        chunk: File,
    ): Response {
        val partial = partialFile(uploadId)
        val currentOffset = partial.takeIf(File::isFile)?.length() ?: 0L
        if (currentOffset != expectedOffset) {
            return jsonResponse(
                Response.Status.CONFLICT,
                JSONObject().put("offset", currentOffset).put("error", "Offset changed"),
            )
        }
        if (currentOffset + chunk.length() > totalBytes) {
            return errorResponse(Response.Status.BAD_REQUEST, "Chunk exceeds file size")
        }

        ensureFreeSpace(chunk.length())
        val uploadDir = uploadDirectory(create = true)
        if (!uploadDir.isDirectory) throw IOException("Upload directory unavailable")
        FileOutputStream(partial, true).use { output ->
            chunk.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_SIZE) }
        }

        val newOffset = partial.length()
        if (newOffset != totalBytes) {
            return jsonResponse(
                Response.Status.OK,
                JSONObject().put("offset", newOffset).put("complete", false),
            )
        }

        val target = IncomingFile.uniqueTarget(targetDir, safeName)
            ?: throw IOException("No free target name")
        IncomingFile.commitPartial(partial, target)
        uploadLocks.remove(uploadId)
        onFileReceived(target.name)
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("offset", newOffset)
                .put("complete", true)
                .put("name", target.name),
        )
    }

    private fun handleLegacyUpload(session: IHTTPSession): Response {
        return try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)

            var saved = 0
            for ((parameter, tempPath) in body) {
                val original = session.parameters[parameter]?.firstOrNull() ?: continue
                val name = IncomingFile.sanitizeName(original) ?: continue
                val target = IncomingFile.uniqueTarget(targetDir, name) ?: continue
                val tempFile = File(tempPath)
                ensureFreeSpace(tempFile.length())
                tempFile.inputStream().use { input -> IncomingFile.copyAtomically(input, target) }
                onFileReceived(target.name)
                saved++
            }
            newFixedLengthResponse(Response.Status.OK, "text/plain", "OK: $saved")
        } catch (e: Exception) {
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "Upload failed")
        }
    }

    private fun uploadDirectory(create: Boolean): File {
        if (create && !targetDir.isDirectory && !targetDir.mkdirs()) {
            throw IOException("Target directory unavailable")
        }
        val dir = File(targetDir, UPLOAD_DIRECTORY_NAME)
        if (create && !dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Upload directory unavailable")
        }
        return dir
    }

    private fun partialFile(uploadId: String): File {
        val directory = uploadDirectory(create = false).canonicalFile
        return File(directory, "$uploadId.partial").canonicalFile.also {
            if (it.parentFile != directory) throw IOException("Invalid upload path")
        }
    }

    private fun cleanupStaleUploads() {
        val cutoff = System.currentTimeMillis() - PARTIAL_MAX_AGE_MILLIS
        uploadDirectory(create = false).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".partial") && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private fun ensureFreeSpace(incomingBytes: Long) {
        val usable = targetDir.usableSpace
        if (usable in 1 until incomingBytes + SPACE_MARGIN_BYTES) {
            throw IOException("Not enough free space")
        }
    }

    private fun IHTTPSession.header(name: String): String? =
        headers[name.lowercase()] ?: headers.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value

    private fun jsonResponse(status: Response.IStatus, json: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())

    private fun errorResponse(status: Response.IStatus, message: String): Response =
        jsonResponse(status, JSONObject().put("error", message))

    private fun isValidUploadId(value: String?): Boolean =
        value != null && UPLOAD_ID.matches(value)

    companion object {
        const val DEFAULT_PORT = 8737
        private const val HEADER_UPLOAD_ID = "X-Thor-Upload-Id"
        private const val HEADER_OFFSET = "X-Thor-Offset"
        private const val HEADER_TOTAL_SIZE = "X-Thor-Total-Size"
        private const val UPLOAD_DIRECTORY_NAME = ".thor_uploads"
        private const val MAX_CHUNK_BYTES = 16L * 1024 * 1024
        private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
        private const val PARTIAL_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private val UPLOAD_ID = Regex("[a-f0-9]{32}")

        // language=HTML
        private val UPLOAD_PAGE = """
            <!DOCTYPE html>
            <html lang="de"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Thor ROM Butler</title>
            <style>
              :root{color-scheme:dark;--bg:#060a14;--panel:#111a2e;--line:#2a3a5c;
                --text:#e6eef8;--muted:#93a5c0;--blue:#38c8ff;--gold:#f5c542;
                --green:#45d483;--red:#ff6b74}
              *{box-sizing:border-box}body{font-family:system-ui,sans-serif;background:var(--bg);
                color:var(--text);margin:0;min-height:100vh;padding:28px 18px}
              main{width:min(780px,100%);margin:0 auto}header{display:flex;align-items:center;
                gap:12px;margin-bottom:24px}h1{font-size:28px;margin:0;color:var(--blue)}
              .bolt{font-size:30px;color:var(--gold)}#drop{border:2px dashed var(--line);
                background:var(--panel);padding:42px 20px;text-align:center;cursor:pointer;
                border-radius:8px;transition:.15s border-color,.15s background}
              #drop.hover{border-color:var(--gold);background:#17223b}#file{margin-top:16px;
                max-width:100%}.hint{color:var(--muted);margin:8px 0 0;font-size:14px}
              #summary{color:var(--muted);margin:18px 0 10px;min-height:22px}.item{
                border-top:1px solid var(--line);padding:15px 0}.top{display:flex;gap:12px;
                align-items:center}.name{font-weight:650;overflow-wrap:anywhere;flex:1}.size{
                color:var(--muted);font-variant-numeric:tabular-nums}.track{height:8px;
                background:#1d2942;margin:10px 0 7px;border-radius:4px;overflow:hidden}.bar{
                height:100%;width:0;background:var(--blue);transition:width .12s linear}.bar.done{
                background:var(--green)}.bar.failed{background:var(--red)}.meta{display:flex;
                justify-content:space-between;gap:12px;color:var(--muted);font-size:14px}.actions{
                margin-top:9px;display:flex;gap:8px}button{border:1px solid var(--line);
                background:#15213a;color:var(--text);padding:8px 12px;border-radius:6px;cursor:pointer}
              button:hover{border-color:var(--blue)}button.danger:hover{border-color:var(--red)}
              button[hidden]{display:none}.error{color:var(--red);margin-top:6px;font-size:14px}
              @media(max-width:520px){body{padding:18px 12px}h1{font-size:23px}#drop{padding:32px 14px}
                .meta{flex-direction:column;gap:2px}}
            </style></head><body><main>
              <header><span class="bolt">&#9889;</span><h1>Thor ROM Butler</h1></header>
              <div id="drop"><strong>ROMs hier ablegen / drop ROMs here</strong><br>
                <input type="file" id="file" multiple>
                <p class="hint">Unterbrochene Dateien werden beim erneuten Auswählen fortgesetzt.<br>
                Interrupted files resume when selected again.</p></div>
              <div id="summary">Bereit / ready</div><div id="queue"></div>
            </main><script>
              const CHUNK=8*1024*1024;
              const drop=document.getElementById('drop'),input=document.getElementById('file');
              const queueEl=document.getElementById('queue'),summary=document.getElementById('summary');
              const items=[];let processing=false;
              function bytes(n){if(n>=1073741824)return(n/1073741824).toFixed(1)+' GB';
                if(n>=1048576)return(n/1048576).toFixed(1)+' MB';return Math.round(n/1024)+' KB'}
              function duration(s){if(!isFinite(s)||s<0)return '–';s=Math.round(s);
                if(s<60)return s+' s';return Math.floor(s/60)+' min '+(s%60)+' s'}
              function hash32(text,seed){let hash=seed>>>0;for(let i=0;i<text.length;i++){
                  hash=Math.imul(hash^text.charCodeAt(i),16777619)}return(hash>>>0).toString(16).padStart(8,'0')}
              function fileId(f){const raw=f.name+'\n'+f.size+'\n'+f.lastModified;
                return hash32(raw,2166136261)+hash32(raw,2246822507)+hash32(raw,3266489909)+hash32(raw,668265263)}
              function render(item){item.nameEl.textContent=item.file.name;item.sizeEl.textContent=bytes(item.file.size);
                const pct=item.file.size?Math.min(100,item.loaded/item.file.size*100):0;
                item.bar.style.width=pct.toFixed(1)+'%';item.percentEl.textContent=pct.toFixed(1)+'%';
                item.bar.className='bar'+(item.state==='done'?' done':item.state==='failed'?' failed':'');
                item.detailEl.textContent=item.detail||'';item.errorEl.textContent=item.error||'';
                item.cancel.hidden=item.state!=='uploading';item.retry.hidden=item.state!=='failed';}
              function makeItem(file){const root=document.createElement('div');root.className='item';
                root.innerHTML='<div class="top"><span class="name"></span><span class="size"></span></div>'+
                  '<div class="track"><div class="bar"></div></div><div class="meta"><span class="percent">0%</span>'+
                  '<span class="detail">Warteschlange / queued</span></div><div class="error"></div>'+
                  '<div class="actions"><button class="cancel danger">Abbrechen / cancel</button>'+
                  '<button class="retry">Wiederholen / retry</button></div>';queueEl.appendChild(root);
                const item={file,state:'queued',loaded:0,detail:'Warteschlange / queued',error:'',xhr:null,
                  nameEl:root.querySelector('.name'),sizeEl:root.querySelector('.size'),bar:root.querySelector('.bar'),
                  percentEl:root.querySelector('.percent'),detailEl:root.querySelector('.detail'),
                  errorEl:root.querySelector('.error'),cancel:root.querySelector('.cancel'),retry:root.querySelector('.retry')};
                item.cancel.onclick=()=>{if(item.xhr)item.xhr.abort()};item.retry.onclick=()=>{
                  item.state='queued';item.error='';item.detail='Warteschlange / queued';render(item);runQueue()};
                render(item);return item}
              function addFiles(files){for(const file of files){if(!file.size)continue;
                  const item=makeItem(file);item.id=fileId(file);items.push(item)}runQueue()}
              async function remoteOffset(item){const r=await fetch('status?id='+encodeURIComponent(item.id),{cache:'no-store'});
                if(!r.ok)throw new Error('Status '+r.status);const data=await r.json();return Number(data.offset)||0}
              function sendChunk(item,offset){return new Promise((resolve,reject)=>{const end=Math.min(offset+CHUNK,item.file.size);
                const fd=new FormData();fd.append('file',item.file.slice(offset,end),item.file.name);
                const xhr=new XMLHttpRequest();item.xhr=xhr;xhr.open('POST','chunk');
                xhr.setRequestHeader('X-Thor-Upload-Id',item.id);xhr.setRequestHeader('X-Thor-Offset',String(offset));
                xhr.setRequestHeader('X-Thor-Total-Size',String(item.file.size));
                xhr.upload.onprogress=e=>{if(!e.lengthComputable)return;item.loaded=offset+e.loaded;
                  const elapsed=Math.max(.25,(performance.now()-item.startedAt)/1000);
                  const speed=Math.max(0,(item.loaded-item.startedAtBytes)/elapsed);
                  item.detail=bytes(speed)+'/s · '+duration((item.file.size-item.loaded)/speed)+' Rest / left';render(item)};
                xhr.onload=()=>{let data={};try{data=JSON.parse(xhr.responseText)}catch(e){}
                  if(xhr.status>=200&&xhr.status<300)resolve(data);else if(xhr.status===409)resolve(data);
                  else reject(new Error(data.error||('HTTP '+xhr.status)))};
                xhr.onerror=()=>reject(new Error('Netzwerkfehler / network error'));
                xhr.onabort=()=>reject(new Error('Abgebrochen / canceled'));xhr.send(fd)})}
              async function upload(item){item.state='uploading';item.error='';item.detail='Prüfe / checking';render(item);
                let offset=await remoteOffset(item);if(offset>item.file.size)throw new Error('Ungültiger Fortsetzungsstand');
                item.loaded=offset;item.startedAt=performance.now();item.startedAtBytes=offset;render(item);
                while(offset<item.file.size){const result=await sendChunk(item,offset);offset=Number(result.offset);
                  if(!Number.isFinite(offset))throw new Error('Ungültige Serverantwort');item.loaded=offset;render(item);
                  if(result.complete)break}item.state='done';item.detail='Fertig / done';item.error='';render(item)}
              async function runQueue(){if(processing)return;processing=true;while(true){const item=items.find(i=>i.state==='queued');
                  if(!item)break;try{await upload(item)}catch(e){item.state='failed';item.error=e.message;
                    item.detail='Fehlgeschlagen / failed';render(item)}finally{item.xhr=null}}
                processing=false;const done=items.filter(i=>i.state==='done').length;
                summary.textContent=done+' / '+items.length+' fertig / done'}
              drop.addEventListener('dragover',e=>{e.preventDefault();drop.classList.add('hover')});
              drop.addEventListener('dragleave',()=>drop.classList.remove('hover'));
              drop.addEventListener('drop',e=>{e.preventDefault();drop.classList.remove('hover');addFiles(e.dataTransfer.files)});
              input.addEventListener('change',e=>{addFiles(e.target.files);input.value='' });
            </script></body></html>
        """.trimIndent()
    }
}
