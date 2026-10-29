package com.traveler.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MigrationButton(enabled: Boolean = true, onImport: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(enabled = enabled, onClick = { show = true }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Text("기존 앱의 여행 가져오기")
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("기존 앱을 남겨 두고 가져오기") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1. 기존 앱에서 여행을 열고 ▶ → Back up this journey로 JSON을 다운로드 폴더에 저장하세요. 여행마다 반복합니다.")
            Text("2. 아래에서 백업을 선택하면 새 여행으로 복원합니다. 기존 앱의 기록을 직접 읽거나 삭제하지 않습니다.")
            Text("3. 사진 연결 확인 화면에서 권한을 허용하세요. 읽을 수 없는 원본은 파일을 직접 선택해 연결합니다. JSON에는 사진 파일이 들어 있지 않습니다.")
            Text("4. 날짜·경로·수정 내용과 사진을 확인한 뒤 ‘사진·동영상 모두 앱에 보관’과 ‘사진·동영상 포함 백업’을 완료하세요.")
            Text("모든 여행의 복원과 사진 확인이 끝날 때까지 기존 앱과 갤러리 원본을 지우지 마세요. 새 앱의 사진 선택 결과·설정은 이전 앱과 다를 수 있습니다.")
        } }, confirmButton = { TextButton(onClick = { show = false; onImport() }) { Text("백업 파일 선택") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("닫기") } })
}
