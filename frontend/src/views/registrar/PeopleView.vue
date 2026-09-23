<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import { useDirtyGuard } from '../../composables/useDirtyGuard'
import { label, today } from '../../utils/format'
import type { PersonRecord } from '../../types/api'
import ErrorNotice from '../../components/ErrorNotice.vue'
const props = defineProps<{ kind: 'students' | 'professors' }>()
const noun = computed(() => (props.kind === 'students' ? '学生' : '教师'))
const items = ref<PersonRecord[]>([]),
  total = ref(0),
  page = ref(1),
  keyword = ref(''),
  loading = ref(false),
  busy = ref(false),
  error = ref<unknown>(null),
  formError = ref<unknown>(null)
const visible = ref(false),
  editing = ref<PersonRecord | null>(null),
  formRef = ref<FormInstance>(),
  blocked = ref(false),
  baseline = ref('')
const form = reactive({
  username: '',
  name: '',
  birthDate: '',
  status: 'ACTIVE',
  graduationDate: '' as string | null,
  department: '',
  socialSecurityNumber: '',
})
const dirty = computed(() => visible.value && JSON.stringify(form) !== baseline.value)
const { confirmLeave } = useDirtyGuard(dirty)
const rules: FormRules = {
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    { min: 1, max: 64, message: '账号不能超过 64 个字符', trigger: 'blur' },
  ],
  name: [
    { required: true, whitespace: true, message: '请输入姓名', trigger: 'blur' },
    { max: 100, message: '姓名不能超过 100 个字符', trigger: 'blur' },
  ],
  birthDate: [
    { required: true, message: '请选择出生日期', trigger: 'change' },
    {
      validator: (_r, value, callback) =>
        value && value > today() ? callback(new Error('出生日期不能晚于今天')) : callback(),
      trigger: 'change',
    },
  ],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
  department: [
    { required: true, whitespace: true, message: '请输入院系', trigger: 'blur' },
    { max: 100, message: '院系不能超过 100 个字符', trigger: 'blur' },
  ],
  graduationDate: [
    {
      validator: (_r, value, callback) =>
        value && value < form.birthDate
          ? callback(new Error('毕业日期不能早于出生日期'))
          : callback(),
      trigger: 'change',
    },
  ],
  socialSecurityNumber: [
    {
      validator: (_r, value: string, callback) =>
        !value || /^(?:\d{9}|\d{3}-\d{2}-\d{4})$/.test(value)
          ? callback()
          : callback(new Error('请输入九位数字或 XXX-XX-XXXX 格式')),
      trigger: 'blur',
    },
  ],
}
let generation = 0,
  active = true
async function load() {
  const token = ++generation
  loading.value = true
  error.value = null
  try {
    const { data } = await (props.kind === 'students'
      ? api.registrar.students(page.value, 20, keyword.value.trim())
      : api.registrar.professors(page.value, 20, keyword.value.trim()))
    if (token === generation) {
      items.value = data.items
      total.value = data.total
    }
  } catch (e) {
    if (token === generation) error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
function search() {
  page.value = 1
  void load()
}
function fill(person: PersonRecord | null) {
  editing.value = person
  formError.value = null
  blocked.value = false
  Object.assign(form, {
    username: person?.username || '',
    name: person?.name || '',
    birthDate: person?.birthDate || '',
    status: person?.status || 'ACTIVE',
    graduationDate: person?.graduationDate || null,
    department: person?.department || '',
    socialSecurityNumber: '',
  })
  baseline.value = JSON.stringify(form)
  visible.value = true
  formRef.value?.clearValidate()
}
async function edit(person: PersonRecord) {
  error.value = null
  busy.value = true
  try {
    const { data } = await api.registrar.person(props.kind, person.id)
    if (active) fill(data)
  } catch (e) {
    if (active) error.value = e
  } finally {
    if (active) busy.value = false
  }
}
async function closeForm(done?: () => void) {
  if (busy.value) return
  if (await confirmLeave()) {
    visible.value = false
    form.socialSecurityNumber = ''
    editing.value = null
    done?.()
  }
}
async function submit() {
  if (busy.value || blocked.value) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  const data: Record<string, unknown> = {
    name: form.name.trim(),
    birthDate: form.birthDate,
    status: form.status,
  }
  if (!editing.value) data.username = form.username.trim()
  if (props.kind === 'students') data.graduationDate = form.graduationDate || null
  else data.department = form.department.trim()
  // Blank on edit means leave the existing identifier unchanged, never send the mask.
  if (form.socialSecurityNumber.trim())
    data.socialSecurityNumber = form.socialSecurityNumber.replaceAll('-', '')
  busy.value = true
  formError.value = null
  try {
    const result =
      props.kind === 'students'
        ? editing.value
          ? await api.registrar.updateStudent(editing.value.id, data, editing.value.version)
          : await api.registrar.createStudent(data)
        : editing.value
          ? await api.registrar.updateProfessor(editing.value.id, data, editing.value.version)
          : await api.registrar.createProfessor(data)
    ElMessage.success(`${noun.value}资料已保存，ID：${result.data.id}`)
    visible.value = false
    form.socialSecurityNumber = ''
    await load()
  } catch (e) {
    formError.value = e
    if (e instanceof HttpError && [0, 412].includes(e.status)) blocked.value = true
  } finally {
    busy.value = false
  }
}
async function remove(person: PersonRecord) {
  try {
    await ElMessageBox.confirm(
      `删除${noun.value}“${person.name}”（ID：${person.id}）将停用账号。存在当前关联时系统会拒绝删除，历史资料继续保留。`,
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = true
  error.value = null
  try {
    if (props.kind === 'students') await api.registrar.deleteStudent(person.id, person.version)
    else await api.registrar.deleteProfessor(person.id, person.version)
    ElMessage.success('已删除并停用账号')
    await load()
  } catch (e) {
    error.value = e
  } finally {
    busy.value = false
  }
}
onMounted(load)
onUnmounted(() => {
  active = false
  generation++
  form.socialSecurityNumber = ''
  items.value = []
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">{{ noun }}信息</h1>
      <p class="page-description">维护{{ noun }}基础资料与状态，保留历史关联信息。</p>
    </div>
    <el-button
      type="primary"
      :disabled="busy"
      @click="fill(null)"
      >新增{{ noun }}</el-button
    >
  </div>
  <ErrorNotice
    :error="error"
    retry
    @retry="load"
  />
  <section class="surface">
    <div class="surface-header">
      <form
        class="filter-row"
        @submit.prevent="search"
      >
        <el-input
          v-model="keyword"
          :placeholder="`按${noun} ID、姓名或账号搜索`"
          :aria-label="`搜索${noun}`"
          clearable
          style="width: 270px"
        /><el-button
          native-type="submit"
          type="primary"
          plain
          >查询</el-button
        >
      </form>
      <span class="tiny muted">共 {{ total }} 条</span>
    </div>
    <div class="surface-body">
      <el-table
        v-loading="loading"
        :data="items"
        empty-text="暂无符合条件的记录"
        ><el-table-column
          prop="id"
          label="ID"
          min-width="110"
        /><el-table-column
          prop="name"
          label="姓名"
          min-width="100"
        /><el-table-column
          prop="username"
          label="账号"
          min-width="120"
        /><el-table-column
          prop="birthDate"
          label="出生日期"
          min-width="115"
        /><el-table-column
          v-if="kind === 'professors'"
          prop="department"
          label="院系"
          min-width="150"
        /><el-table-column
          v-else
          label="毕业日期"
          min-width="115"
          ><template #default="{ row }">{{ row.graduationDate || '—' }}</template></el-table-column
        ><el-table-column
          label="社会安全号码"
          min-width="145"
          ><template #default="{ row }">{{ row.ssnMasked || '未填写' }}</template></el-table-column
        ><el-table-column
          label="状态"
          width="90"
          ><template #default="{ row }"
            ><el-tag
              :type="row.status === 'ACTIVE' ? 'success' : 'info'"
              size="small"
              >{{ label(row.status) }}</el-tag
            ></template
          ></el-table-column
        ><el-table-column
          label="操作"
          width="125"
          fixed="right"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              :disabled="busy"
              @click="edit(row)"
              >编辑</el-button
            ><el-button
              link
              type="danger"
              :disabled="busy"
              @click="remove(row)"
              >删除</el-button
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="prev, pager, next, total"
        small
        class="pagination"
        @current-change="load"
      />
    </div>
  </section>
  <el-dialog
    v-model="visible"
    :title="`${editing ? '编辑' : '新增'}${noun}`"
    width="min(620px, 94vw)"
    :before-close="closeForm"
    :close-on-click-modal="false"
    destroy-on-close
    ><ErrorNotice :error="formError" /><el-alert
      v-if="blocked"
      title="操作结果可能已变化。请取消编辑并重新查询，核对后再修改。"
      type="warning"
      :closable="false"
    /><el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      :disabled="busy || blocked"
      @submit.prevent="submit"
      ><el-form-item
        v-if="!editing"
        label="登录账号"
        prop="username"
        ><el-input
          v-model="form.username"
          maxlength="64"
          autocomplete="off" /></el-form-item
      ><el-form-item
        v-else
        label="ID / 登录账号"
        ><el-input
          :model-value="`${editing.id} / ${editing.username || '—'}`"
          disabled
      /></el-form-item>
      <div class="form-grid">
        <el-form-item
          label="姓名"
          prop="name"
          ><el-input
            v-model="form.name"
            maxlength="100" /></el-form-item
        ><el-form-item
          label="出生日期"
          prop="birthDate"
          ><el-date-picker
            v-model="form.birthDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
            :editable="false" /></el-form-item
        ><el-form-item
          label="状态"
          prop="status"
          ><el-select v-model="form.status"
            ><el-option
              label="正常"
              value="ACTIVE" /><el-option
              label="停用"
              value="INACTIVE" /><el-option
              v-if="kind === 'students'"
              label="已毕业"
              value="GRADUATED" /></el-select></el-form-item
        ><el-form-item
          v-if="kind === 'students'"
          label="毕业日期（可空）"
          prop="graduationDate"
          ><el-date-picker
            v-model="form.graduationDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="在读可不填写"
            style="width: 100%"
            :editable="false" /></el-form-item
        ><el-form-item
          v-else
          label="院系"
          prop="department"
          ><el-input
            v-model="form.department"
            maxlength="100"
        /></el-form-item>
      </div>
      <el-form-item
        label="社会安全号码（可空）"
        prop="socialSecurityNumber"
        ><el-input
          v-model="form.socialSecurityNumber"
          type="password"
          maxlength="11"
          autocomplete="new-password"
          :placeholder="
            editing?.ssnMasked
              ? `已保存 ${editing.ssnMasked}；留空保持原值`
              : '九位数字或 XXX-XX-XXXX'
          "
      /></el-form-item>
      <p
        v-if="!editing"
        class="tiny muted"
      >
        密码由受控初始化流程提供，此页面不生成或展示密码。
      </p></el-form
    ><template #footer
      ><el-button
        :disabled="busy"
        @click="closeForm()"
        >取消</el-button
      ><el-button
        type="primary"
        :loading="busy"
        :disabled="blocked"
        @click="submit"
        >保存</el-button
      ></template
    ></el-dialog
  >
</template>
