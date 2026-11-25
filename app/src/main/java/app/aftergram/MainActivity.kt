package app.aftergram

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

// ---------- colors ----------

private val Brand = Color(0xFF4A2BB0)
private val OutBubble = Color(0xFFE5DCFA)
private val ChatBg = Color(0xFFF2EFFB)

// ---------- models ----------

data class Me(val id: Int, val username: String, val displayName: String)

data class Chat(
    val id: Int,
    val peerId: Int,
    val peerName: String,
    val peerUsername: String,
    val lastText: String?,
    val lastAt: Double?,
)

data class Msg(
    val id: Int,
    val chatId: Int,
    val senderId: Int,
    val text: String,
    val createdAt: Double,
    val clientId: String? = null,
    val pending: Boolean = false,
)

data class UserHit(val id: Int, val username: String, val displayName: String)

class ApiError(message: String) : Exception(message)

private fun toMsg(m: JSONObject) = Msg(
    m.getInt("id"), m.getInt("chat_id"), m.getInt("sender_id"),
    m.getString("text"), m.getDouble("created_at"),
)

fun timeOf(sec: Double): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((sec * 1000).toLong()))

// ---------- network ----------

private val HTTP = OkHttpClient()

class Api(val base: String) {
    var token: String? = null
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun call(method: String, path: String, body: JSONObject? = null): String {
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        val rb = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "GET" -> null
            else -> "".toRequestBody(jsonType)
        }
        builder.method(method, rb)
        HTTP.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val detail = try {
                    JSONObject(text).optString("detail", "Ошибка ${r.code}")
                } catch (e: Exception) {
                    "Ошибка ${r.code}"
                }
                throw ApiError(detail)
            }
            return text
        }
    }
}

// ---------- state ----------

class AppVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("aftergram", Context.MODE_PRIVATE)

    var serverUrl by mutableStateOf(prefs.getString("server", "http://") ?: "http://")
    var me by mutableStateOf<Me?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var current by mutableStateOf<Chat?>(null)
    var showSearch by mutableStateOf(false)
    var typingChat by mutableStateOf<Int?>(null)
    val chats = mutableStateListOf<Chat>()
    val messages = mutableStateListOf<Msg>()
    val hits = mutableStateListOf<UserHit>()

    private var api: Api? = null
    private var ws: WebSocket? = null
    private var loggedIn = false
    private var lastTyping = 0L

    init {
        val t = prefs.getString("token", null)
        val s = prefs.getString("server", null)
        if (t != null && s != null) {
            val a = Api(s)
            a.token = t
            viewModelScope.launch {
                try {
                    val u = withContext(Dispatchers.IO) { JSONObject(a.call("GET", "/me")) }
                    api = a
                    me = Me(u.getInt("id"), u.getString("username"), u.getString("display_name"))
                    loggedIn = true
                    refreshChats()
                    connectWs()
                } catch (e: ApiError) {
                    prefs.edit().remove("token").apply()
                } catch (e: Exception) {
                    error = "Нет связи с сервером"
                }
            }
        }
    }

    fun auth(register: Boolean, server: String, username: String, display: String, password: String) {
        val s = server.trim().trimEnd('/')
        busy = true
        viewModelScope.launch {
            try {
                val a = Api(s)
                val body = JSONObject()
                    .put("username", username.trim())
                    .put("password", password)
                if (register) body.put("display_name", display.trim().ifEmpty { username.trim() })
                val path = if (register) "/register" else "/login"
                val res = withContext(Dispatchers.IO) { JSONObject(a.call("POST", path, body)) }
                val token = res.getString("token")
                prefs.edit().putString("server", s).putString("token", token).apply()
                serverUrl = s
                a.token = token
                api = a
                val u = res.getJSONObject("user")
                me = Me(u.getInt("id"), u.getString("username"), u.getString("display_name"))
                loggedIn = true
                refreshChats()
                connectWs()
            } catch (e: Exception) {
                error = e.message ?: "Ошибка"
            }
            busy = false
        }
    }

    fun logout() {
        loggedIn = false
        ws?.close(1000, null)
        ws = null
        api = null
        prefs.edit().remove("token").apply()
        me = null
        current = null
        showSearch = false
        chats.clear()
        messages.clear()
    }

    private suspend fun refreshChats() {
        val a = api ?: return
        val arr = withContext(Dispatchers.IO) { JSONArray(a.call("GET", "/chats")) }
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = o.getJSONObject("peer")
            val lm = o.optJSONObject("last_message")
            Chat(
                o.getInt("id"), p.getInt("id"), p.getString("display_name"), p.getString("username"),
                lm?.getString("text"), lm?.getDouble("created_at"),
            )
        }
        chats.clear()
        chats.addAll(list)
    }

    fun openChat(c: Chat) {
        current = c
        messages.clear()
        viewModelScope.launch {
            try {
                val a = api ?: return@launch
                val arr = withContext(Dispatchers.IO) {
                    JSONArray(a.call("GET", "/chats/${c.id}/messages?limit=100"))
                }
                val list = (0 until arr.length()).map { toMsg(arr.getJSONObject(it)) }
                if (current?.id == c.id) {
                    messages.clear()
                    messages.addAll(list)
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun send(text: String) {
        val c = current ?: return
        val u = me ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        val cid = UUID.randomUUID().toString()
        messages.add(Msg(-1, c.id, u.id, t, System.currentTimeMillis() / 1000.0, cid, true))
        val payload = JSONObject()
            .put("type", "send")
            .put("chat_id", c.id)
            .put("text", t)
            .put("client_id", cid)
        val ok = ws?.send(payload.toString()) ?: false
        if (!ok) error = "Нет соединения"
    }

    fun typing() {
        val c = current ?: return
        val now = System.currentTimeMillis()
        if (now - lastTyping < 3000) return
        lastTyping = now
        ws?.send(JSONObject().put("type", "typing").put("chat_id", c.id).toString())
    }

    fun search(q: String) {
        if (q.isBlank()) {
            hits.clear()
            return
        }
        viewModelScope.launch {
            try {
                val a = api ?: return@launch
                val enc = URLEncoder.encode(q.trim(), "UTF-8")
                val arr = withContext(Dispatchers.IO) { JSONArray(a.call("GET", "/users/search?q=$enc")) }
                hits.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    hits.add(UserHit(o.getInt("id"), o.getString("username"), o.getString("display_name")))
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun startChat(u: UserHit) {
        viewModelScope.launch {
            try {
                val a = api ?: return@launch
                val body = JSONObject().put("user_id", u.id)
                val res = withContext(Dispatchers.IO) { JSONObject(a.call("POST", "/chats/direct", body)) }
                val id = res.getInt("id")
                refreshChats()
                val chat = chats.firstOrNull { it.id == id }
                    ?: Chat(id, u.id, u.displayName, u.username, null, null)
                hits.clear()
                showSearch = false
                openChat(chat)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    // ----- websocket -----

    private fun connectWs() {
        val a = api ?: return
        ws?.close(1000, null)
        val url = a.base.trimEnd('/').replaceFirst("http", "ws") + "/ws?token=" + a.token
        ws = HTTP.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch { handleEvent(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reconnect(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnect(webSocket)
            }
        })
    }

    private fun reconnect(old: WebSocket) {
        if (!loggedIn || old !== ws) return
        viewModelScope.launch {
            delay(3000)
            if (loggedIn && old === ws) connectWs()
        }
    }

    private suspend fun handleEvent(text: String) {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (o.optString("type")) {
            "message" -> {
                val msg = toMsg(o.getJSONObject("message"))
                val cid = if (o.isNull("client_id")) "" else o.getString("client_id")
                if (current?.id == msg.chatId) {
                    val idx = if (cid.isNotEmpty()) messages.indexOfFirst { it.clientId == cid } else -1
                    if (idx >= 0) {
                        messages[idx] = msg
                    } else if (messages.none { it.id == msg.id }) {
                        messages.add(msg)
                    }
                }
                typingChat = null
                try {
                    refreshChats()
                } catch (e: Exception) {
                    // ignore, the list refreshes on the next event
                }
            }
            "typing" -> {
                val c = o.getInt("chat_id")
                typingChat = c
                delay(3000)
                if (typingChat == c) typingChat = null
            }
            "error" -> error = o.optString("detail")
        }
    }
}

// ---------- UI ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Brand)) {
                Root()
            }
        }
    }
}

@Composable
fun Root(vm: AppVM = viewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.error) {
        vm.error?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.error = null
        }
    }
    BackHandler(enabled = vm.current != null || vm.showSearch) {
        if (vm.current != null) vm.current = null else vm.showSearch = false
    }
    val cur = vm.current
    when {
        vm.me == null -> LoginScreen(vm)
        cur != null -> ChatScreen(vm, cur)
        vm.showSearch -> SearchScreen(vm)
        else -> ChatListScreen(vm)
    }
}

@Composable
fun LoginScreen(vm: AppVM) {
    var server by remember { mutableStateOf(vm.serverUrl) }
    var user by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Aftergram", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Brand)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            server, { server = it }, label = { Text("Адрес сервера") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            user, { user = it }, label = { Text("Имя пользователя (латиницей)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (register) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                name, { name = it }, label = { Text("Как тебя показывать") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Пароль (от 8 символов)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.auth(register, server, user, name, pass) },
            enabled = !vm.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) { Text(if (register) "Создать аккаунт" else "Войти") }
        TextButton(onClick = { register = !register }) {
            Text(if (register) "У меня уже есть аккаунт" else "Создать аккаунт")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun barColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Brand,
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(vm: AppVM) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aftergram", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = { vm.logout() }) { Text("Выйти", color = Color.White) } },
                colors = barColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.showSearch = true },
                containerColor = Brand,
                contentColor = Color.White,
            ) { Icon(Icons.Default.Add, contentDescription = "Новый чат") }
        },
    ) { pad ->
        if (vm.chats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(
                    "Чатов пока нет. Нажми +, чтобы найти человека.",
                    color = Color.Gray, modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(vm.chats, key = { it.id }) { c -> ChatRow(c) { vm.openChat(c) } }
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: Int = 48) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Brand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(), color = Color.White,
            fontWeight = FontWeight.Bold, fontSize = (size / 2.4).sp,
        )
    }
}

@Composable
fun ChatRow(c: Chat, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.peerName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.peerName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                c.lastText ?: "Нет сообщений", color = Color.Gray,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        c.lastAt?.let { Text(timeOf(it), color = Color.Gray, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: AppVM) {
    var q by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новый чат") },
                navigationIcon = {
                    IconButton(onClick = { vm.showSearch = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = barColors(),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                q, { q = it; vm.search(it) }, label = { Text("Имя пользователя") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            LazyColumn {
                items(vm.hits, key = { it.id }) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.startChat(h) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(h.displayName)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(h.displayName, fontWeight = FontWeight.SemiBold)
                            Text("@" + h.username, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppVM, chat: Chat) {
    val myId = vm.me?.id ?: 0
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat.peerName, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (vm.typingChat == chat.id) Text("печатает…", fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.current = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = barColors(),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(ChatBg)) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(vm.messages, key = { it.clientId ?: "s${it.id}" }) { m ->
                    Bubble(m, m.senderId == myId)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    text, { text = it; if (it.isNotEmpty()) vm.typing() },
                    placeholder = { Text("Сообщение") },
                    modifier = Modifier.weight(1f), maxLines = 4,
                )
                IconButton(onClick = { vm.send(text); text = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = Brand)
                }
            }
        }
    }
}

@Composable
fun Bubble(m: Msg, mine: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (mine) OutBubble else Color.White)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(m.text, fontSize = 16.sp)
            Text(
                if (m.pending) "…" else timeOf(m.createdAt),
                fontSize = 11.sp, color = Color.Gray,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
