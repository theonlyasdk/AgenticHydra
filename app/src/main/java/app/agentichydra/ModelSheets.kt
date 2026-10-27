package app.agentichydra

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable fun ComposerModelControl(vm: HydraModel, enabled: Boolean = true, onOpen: () -> Unit) {
    TextButton(onClick=onOpen, enabled=enabled && !vm.busy, contentPadding=PaddingValues(horizontal=12.dp), modifier=Modifier.heightIn(min=48.dp).widthIn(max=300.dp)) {
        BrandIcon(vm.model,vm.provider,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        MarqueeTitle(vm.selectedModel.name.ifBlank { "Choose model" },surface=MaterialTheme.colorScheme.surfaceContainer,style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
        if(vm.effort!="auto") { Spacer(Modifier.width(6.dp)); Text("· ${ReasoningPolicy.label(vm.reasoning,vm.effort)}",maxLines=1) }
        Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable fun ModelPickerSheet(vm: HydraModel,onConnections:()->Unit,onDismiss:()->Unit) {
    val cs=MaterialTheme.colorScheme
    var query by rememberSaveable(vm.provider) {mutableStateOf("")}
    var manual by rememberSaveable(vm.provider) {mutableStateOf(false)}
    var manualId by rememberSaveable(vm.provider) {mutableStateOf("")}
    val pendingManual=manual&&manualId.isNotBlank()
    val availability=ModelPickerAvailabilityUiState(vm.provider,vm.apiKey.isNotBlank(),vm.models.isNotEmpty(),vm.customModelSelected||pendingManual)
    val filtered=remember(vm.models,query,availability.needsConnection) {
        if(availability.needsConnection)emptyList()else vm.models.filter{it.id.contains(query,true)||it.name.contains(query,true)||it.owner.contains(query,true)}
    }
    val keyboard=LocalSoftwareKeyboardController.current
    val clipboard=LocalClipboardManager.current
    var copyTarget by remember { mutableStateOf<ModelSpec?>(null) }
    val modelListState=rememberLazyListState(initialFirstVisibleItemIndex=filtered.indexOfFirst{it.id==vm.model}.coerceAtLeast(0))
    val keyboardOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    LaunchedEffect(vm.provider){
        delay(350)
        if(!vm.discovering&&(vm.models.isEmpty()||System.currentTimeMillis()-vm.catalogUpdated>300_000))vm.refreshModels()
    }
    var connectionsAfterDismiss by remember { mutableStateOf(false) }
    HydraBottomSheet(onDismiss={if(connectionsAfterDismiss)onConnections()else onDismiss()},containerColor=cs.surfaceContainerLow) { dismiss ->
        val connect = { if(pendingManual)vm.chooseModel(manualId,manual=true);connectionsAfterDismiss=true; dismiss() }
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("models-header"),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome,null,tint=cs.primary)
                Spacer(Modifier.width(12.dp))
                Text("Models",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                IconButton(onClick={vm.refreshModels()},enabled=!vm.discovering&&!availability.needsConnection){Icon(Icons.Outlined.Refresh,"Refresh models")}
                IconButton(onClick=connect){Icon(Icons.Outlined.Tune,"Provider connections")}
            }
          Column(Modifier.fillMaxWidth().weight(1f,fill=false).sheetContentScrollOnly().padding(horizontal=16.dp)) {
            Spacer(Modifier.height(8.dp))
            ProviderChoices(vm.provider,onSelect={p->vm.switchProvider(p);query=""})
            if(availability.showSearch)SearchBar(inputField={SearchBarDefaults.InputField(query=query,onQueryChange={query=it},onSearch={keyboard?.hide()},expanded=false,onExpandedChange={},
                placeholder={Text("Search models")},leadingIcon={Icon(Icons.Outlined.Search,null)})},expanded=false,onExpandedChange={},
                shape=MaterialTheme.shapes.extraLarge,windowInsets=WindowInsets(0),modifier=Modifier.fillMaxWidth().padding(top=8.dp)){}
            if(vm.discovering&&!availability.needsConnection)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(availability.hasModels&&!availability.needsConnection)vm.discoveryError?.let{error->
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Info,null,Modifier.size(20.dp),tint=cs.primary);Spacer(Modifier.width(8.dp));Text(error,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))}
            }
            LaunchedEffect(vm.provider,vm.model,query,filtered) {
                val index=filtered.indexOfFirst{it.id==vm.model}
                if(index>=0) {
                    val layout=modelListState.layoutInfo
                    val selected=layout.visibleItemsInfo.firstOrNull{it.index==index}
                    if(selected==null||selected.offset<layout.viewportStartOffset||selected.offset+selected.size>layout.viewportEndOffset)
                        modelListState.scrollToItem(index)
                }
            }
            LazyColumn(Modifier.weight(1f,fill=false).verticalFadingEdges(cs.surfaceContainerLow,24.dp).testTag("model-list"),
                state=modelListState,
                contentPadding=PaddingValues(top=if(availability.showSearch)24.dp else 12.dp,bottom=24.dp)) {
                if(availability.needsConnection)item {
                    ModelPickerEmptyState(ModelPickerEmptyUiState("Add a ${vm.provider.label} API key",needsKey=true))
                } else if(filtered.isEmpty()) item {
                    ModelPickerEmptyState(ModelPickerEmptyUiState(
                        if(vm.discovering)"Loading models…" else if(query.isBlank()||!availability.hasModels)"No models loaded" else "No matching models",
                        detail=if(!availability.hasModels&&!vm.discovering)vm.discoveryError else null))
                }
                itemsIndexed(filtered,key={_,m->m.id}){index,m->
                    val selected=vm.model==m.id
                    val rowColor=if(selected)cs.primaryContainer else cs.surfaceContainerHigh
                    Surface(shape=groupedRowShape(index,filtered.size),color=rowColor,modifier=Modifier.fillMaxWidth().padding(bottom=4.dp).semantics{this.selected=selected}
                        .combinedClickable(onClick={vm.chooseModel(m.id);keyboard?.hide()},onLongClickLabel="Copy model name",onLongClick={copyTarget=m})) {
                        ListItem(headlineContent={MarqueeTitle(m.name,surface=rowColor)},
                            supportingContent={if(m.name!=m.id)MarqueeTitle(m.id,surface=rowColor,style=MaterialTheme.typography.labelMedium)},
                            leadingContent={BrandIcon(m.id,vm.provider,Modifier.size(24.dp))},
                            trailingContent={if(selected)Icon(Icons.Outlined.Check,"Selected",tint=cs.onPrimaryContainer)},
                            colors=ListItemDefaults.colors(containerColor=rowColor,headlineColor=if(selected)cs.onPrimaryContainer else cs.onSurface,
                                supportingColor=if(selected)cs.onPrimaryContainer else cs.onSurfaceVariant))
                    }
                }
            }
            TextButton(onClick={manual=!manual},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.Edit,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Enter model ID")}
            AnimatedVisibility(visible=manual,enter=slideInVertically(tween(140,easing=LinearOutSlowInEasing)){it/2}+fadeIn(tween(100)),
                exit=slideOutVertically(tween(90,easing=LinearOutSlowInEasing)){it/2}+fadeOut(tween(80))){
                Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(value=manualId,onValueChange={manualId=it},label={Text("Model ID")},modifier=Modifier.weight(1f),singleLine=true,shape=MaterialTheme.shapes.medium);IconButton(onClick={vm.chooseModel(manualId,manual=true);manual=false;keyboard?.hide()},enabled=manualId.isNotBlank()){Icon(Icons.Outlined.Check,"Use model ID")}}
            }
            if(!availability.needsConnection&&!keyboardOpen&&vm.reasoning.available)ReasoningControl(vm)

            Button(onClick={if(availability.needsConnection)connect()else{if(pendingManual)vm.chooseModel(manualId,manual=true);dismiss()}},
                enabled=availability.needsConnection||availability.canFinish,shape=MaterialTheme.shapes.extraLarge,
                modifier=Modifier.fillMaxWidth().padding(top=8.dp,bottom=24.dp).heightIn(min=52.dp)){Text(if(availability.needsConnection)"Connect" else "Done")}
          }
        }
    }
    copyTarget?.let { model ->
        ModelCopyDialog(model,onCopy={fullName->
            clipboard.setText(AnnotatedString(if(fullName)model.id else model.name))
            copyTarget=null
        },onDismiss={copyTarget=null})
    }
}

@Composable fun ModelCopyDialog(model:ModelSpec,onCopy:(Boolean)->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,icon={Icon(Icons.Outlined.ContentCopy,null)},
        title={Text(model.name)},text={
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick={onCopy(true)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Copy full model name")}
                TextButton(onClick={onCopy(false)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Copy model name")}
            }
        },confirmButton={})
}

@Preview(name="Copy model light")
@Preview(name="Copy model dark",uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Copy model large text",fontScale=1.5f)
@Composable private fun ModelCopyPreview(){HydraTheme{ModelCopyDialog(ModelSpec("provider/example-model",name="Example model"),{}, {})}}

data class ModelPickerAvailabilityUiState(val provider:Provider,val keyProvided:Boolean,val hasModels:Boolean,val customModelSelected:Boolean) {
    val needsConnection get()=provider.keyRequired&&!keyProvided
    val showSearch get()=hasModels&&!needsConnection
    val canFinish get()=!needsConnection&&(hasModels||customModelSelected)
}

data class ModelPickerEmptyUiState(val title:String,val needsKey:Boolean=false,val detail:String?=null)

@Composable fun ModelPickerEmptyState(state:ModelPickerEmptyUiState) {
    Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(if(state.needsKey)Icons.Outlined.Key else Icons.AutoMirrored.Outlined.ManageSearch,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
        Text(state.title,style=MaterialTheme.typography.bodyLarge,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        state.detail?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
    }
}

@Preview(name="Models empty light")
@Preview(name="Models empty dark",uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Models empty large text",fontScale=1.5f)
@Composable private fun ModelPickerEmptyPreview(){HydraTheme{Surface(color=MaterialTheme.colorScheme.surfaceContainerLow){ModelPickerEmptyState(ModelPickerEmptyUiState("Add a Gemini API key",needsKey=true))}}}

data class ReasoningSelectorUiState(val levels:List<String>,val selected:String)

@Composable fun ProviderChoices(selected:Provider,onSelect:(Provider)->Unit) {
    val scroll=rememberScrollState()
    val direction=LocalLayoutDirection.current
    Row(Modifier.fillMaxWidth().horizontalScrollFadingEdges(scroll,MaterialTheme.colorScheme.surfaceContainerLow,direction)
        .horizontalScroll(scroll),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Provider.entries.forEach { provider ->
            // Default M3 chips are visually compact and retain their 48dp minimum touch target.
            FilterChip(selected=selected==provider,onClick={onSelect(provider)},
                shape=MaterialTheme.shapes.small,label={Text(provider.label)})
        }
    }
}

@Preview(name="Providers light")
@Preview(name="Providers dark",uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name="Providers large text",fontScale=1.5f)
@Composable private fun ProvidersPreview(){HydraTheme{ProviderChoices(Provider.NIM,{})}}

@Composable fun groupedRowShape(index:Int,count:Int):androidx.compose.ui.graphics.Shape {
    val large=MaterialTheme.shapes.large;val small=MaterialTheme.shapes.extraSmall
    return large.copy(topStart=if(index==0)large.topStart else small.topStart,topEnd=if(index==0)large.topEnd else small.topEnd,
        bottomStart=if(index==count-1)large.bottomStart else small.bottomStart,bottomEnd=if(index==count-1)large.bottomEnd else small.bottomEnd)
}
@Composable fun ReasoningControl(vm:HydraModel){
    ReasoningSelector(ReasoningSelectorUiState(listOf("auto")+vm.reasoning.levels,vm.effort),vm::chooseEffort)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReasoningSelector(state:ReasoningSelectorUiState,onSelect:(String)->Unit){
    val cs=MaterialTheme.colorScheme
    if(state.levels.size<2)return
    val position=state.levels.indexOf(state.selected).coerceAtLeast(0).toFloat()
    val animated by animateFloatAsState(position,animationSpec=spring(dampingRatio=.85f,stiffness=450f),label="Reasoning pill")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=8.dp)){
        val segmentWidth=maxWidth/state.levels.size
        val travelPx=with(LocalDensity.current){(maxWidth-segmentWidth).toPx()}
        val latestSelect by rememberUpdatedState(onSelect)
        Surface(color=cs.surfaceContainerHigh,shape=MaterialTheme.shapes.extraLarge,modifier=Modifier.fillMaxWidth()){
            Box(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("reasoning-control").pointerInput(state.levels){
                fun choose(x:Float){latestSelect(state.levels[(x/size.width*state.levels.size).toInt().coerceIn(0,state.levels.lastIndex)])}
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                    var dragging=false
                    do {
                        val event=awaitPointerEvent(PointerEventPass.Initial)
                        val change=event.changes.firstOrNull{it.id==down.id}?:break
                        val delta=change.position-down.position
                        if(abs(delta.x)>viewConfiguration.touchSlop&&abs(delta.x)>abs(delta.y))dragging=true
                        if(dragging){change.consume();choose(change.position.x)}
                    } while(change.pressed)
                }
            }){
                Slider(value=animated,onValueChange={latestSelect(state.levels[it.roundToInt().coerceIn(0,state.levels.lastIndex)])},
                    valueRange=0f..state.levels.lastIndex.toFloat(),steps=(state.levels.size-2).coerceAtLeast(0),
                    modifier=Modifier.matchParentSize().semantics{contentDescription="Reasoning effort"},
                    thumb={},track={})
                Box(Modifier.matchParentSize()){
                    Surface(color=cs.primary,shape=MaterialTheme.shapes.extraLarge,
                        modifier=Modifier.offset { IntOffset((animated/state.levels.lastIndex*travelPx).roundToInt(),0) }
                            .width(segmentWidth).fillMaxHeight().testTag("reasoning-pill")){}
                }
                Row(Modifier.fillMaxWidth().animateContentSize(animationSpec=spring())){
                    state.levels.forEach{level->
                        TextButton(onClick={latestSelect(level)},shape=MaterialTheme.shapes.extraLarge,
                            colors=ButtonDefaults.textButtonColors(contentColor=if(level==state.selected)cs.onPrimary else cs.onSurface),
                            contentPadding=PaddingValues(horizontal=4.dp,vertical=12.dp),modifier=Modifier.weight(1f).heightIn(min=56.dp)){
                            Text(ReasoningPolicy.label(ReasoningProfile(state.levels.filter{it!="auto"}),level),style=MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
@Preview(name="Reasoning light")
@Composable private fun ReasoningLightPreview(){HydraTheme(darkTheme=false,dynamicColor=false){ReasoningSelector(ReasoningSelectorUiState(listOf("low","medium","high"),"medium"),{})}}
@Preview(name="Reasoning dark",uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable private fun ReasoningDarkPreview(){HydraTheme(darkTheme=true,dynamicColor=false){ReasoningSelector(ReasoningSelectorUiState(listOf("low","medium","high"),"medium"),{})}}
@Preview(name="Reasoning large text",fontScale=1.5f)
@Composable private fun ReasoningLargePreview(){HydraTheme{ReasoningSelector(ReasoningSelectorUiState(listOf("auto","low","medium","high"),"high"),{})}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConnectionSheet(vm: HydraModel,onDismiss:()->Unit) {
    var selected by remember {mutableStateOf(vm.provider)}
    var url by remember {mutableStateOf(vm.endpoint)}
    var key by remember {mutableStateOf(vm.apiKey)}
    var reveal by remember {mutableStateOf(false)}
    var problem by remember {mutableStateOf<String?>(null)}
    var manualModel by remember {mutableStateOf("")}
    var showManualModel by remember {mutableStateOf(false)}
    var check by remember { mutableStateOf(ConnectionCheckUiState()) }
    val validator = remember { ConnectionValidator() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var checkJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(validator) { onDispose { checkJob?.cancel(); validator.cancel() } }
    val cs=MaterialTheme.colorScheme
    HydraBottomSheet(onDismiss=onDismiss,containerColor=cs.surfaceContainerLow) { dismiss ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("connections-header"),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Hub,null,tint=cs.primary);Spacer(Modifier.width(12.dp));Text("Connections",style=MaterialTheme.typography.titleLarge)}
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            fun formProblem(): String? {
                val parsed=runCatching{java.net.URI(url.trim())}.getOrNull()
                return when {
                    parsed?.scheme !in listOf("https","http")||parsed?.host.isNullOrBlank()->"Enter a valid HTTP or HTTPS base URL."
                    parsed?.userInfo!=null||parsed?.query!=null||parsed?.fragment!=null->"Use a base URL without credentials or query parameters."
                    selected.keyRequired&&key.isBlank()->"Enter your ${selected.label} API key."
                    parsed?.scheme=="http"&&key.isNotBlank()->"Use HTTPS when sending an API key."
                    else->null
                }
            }
            fun preferredModel() = manualModel.trim().ifBlank { if(url.trim().trimEnd('/')==vm.savedEndpoint(selected).trimEnd('/'))vm.savedModel(selected) else "" }
            AnimatedContent(targetState=check.phase,label="Connection check content",transitionSpec={
                (fadeIn(tween(120)) togetherWith fadeOut(tween(80))).using(null)
            }) { phase ->
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    when(phase) {
                        ConnectionCheckPhase.Checking -> {
                            ConnectionCheckStatus()
                            TextButton(onClick={checkJob?.cancel();validator.cancel();check=ConnectionCheckUiState()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Cancel check")}
                        }
                        ConnectionCheckPhase.Verified -> {
                            ListItem(headlineContent={Text(if(key.isBlank())"Connection verified" else "API key verified")},
                                supportingContent={Text("${check.modelName} accepted the check. Your connection is saved.")},
                                leadingContent={Icon(Icons.Outlined.CheckCircleOutline,null,tint=cs.primary)},
                                colors=ListItemDefaults.colors(containerColor=cs.surfaceContainerLow))
                            Button(onClick=dismiss,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=MaterialTheme.shapes.extraLarge){Text("Done")}
                            TextButton(onClick={check=ConnectionCheckUiState()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Edit connection")}
                        }
                        ConnectionCheckPhase.Editing -> {
                            ProviderChoices(selected,onSelect={p->selected=p;url=vm.savedEndpoint(p);key=vm.savedKey(p);problem=null;manualModel="";showManualModel=false;check=ConnectionCheckUiState()})
                            OutlinedTextField(value=url,onValueChange={url=it;problem=null;check=ConnectionCheckUiState()},label={Text("Base URL")},leadingIcon={Icon(Icons.Outlined.Link,null)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=MaterialTheme.shapes.medium,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                            OutlinedTextField(value=key,onValueChange={key=it;problem=null;check=ConnectionCheckUiState()},label={Text(if(selected.keyRequired)"API key" else "API key (optional)")},leadingIcon={Icon(Icons.Outlined.Key,null)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=MaterialTheme.shapes.medium,
                                visualTransformation=if(reveal)VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={IconButton(onClick={reveal=!reveal}){Icon(if(reveal)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(reveal)"Hide API key" else "Show API key")}})
                            if(showManualModel) {
                                OutlinedTextField(value=manualModel,onValueChange={manualModel=it;check=ConnectionCheckUiState()},
                                    label={Text("Model ID (optional)")},singleLine=true,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth())
                            } else TextButton(onClick={manualModel=preferredModel();showManualModel=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Use a model ID")}
                            problem?.let {Text(it,color=cs.error,style=MaterialTheme.typography.bodyMedium)}
                            if(check.message.isNotBlank())Text(check.message,color=cs.error,style=MaterialTheme.typography.bodyMedium)
                            Button(onClick={
                                problem=formProblem()
                                if(problem==null){
                                    keyboard?.hide()
                                    val provider=selected;val endpoint=url.trim();val credential=key.trim();val model=preferredModel()
                                    check=ConnectionCheckUiState(ConnectionCheckPhase.Checking)
                                    checkJob=scope.launch {
                                        try {
                                            val verified=validator.verify(provider,endpoint,credential,model)
                                            ensureActive()
                                            vm.configure(provider,endpoint,model,credential)
                                            if(manualModel.isNotBlank())vm.chooseModel(model,manual=true)
                                            check=ConnectionCheckUiState(ConnectionCheckPhase.Verified,modelName=verified.name)
                                        } catch(e:CancellationException){throw e}
                                        catch(e:Exception){ensureActive();check=ConnectionCheckUiState(message=if(e is java.io.IOException||e is IllegalArgumentException)e.message?:"Couldn’t verify the connection." else "Couldn’t verify the connection. Check the URL and try again.");if(check.message.contains("model",true))showManualModel=true}
                                    }
                                }
                            },enabled=!vm.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=MaterialTheme.shapes.extraLarge){Icon(Icons.Outlined.VerifiedUser,null);Spacer(Modifier.width(8.dp));Text(if(check.message.isBlank())"Check & save connection" else "Try again")}
                        }
                    }
                }
            }
            }
        }
    }
}
