<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
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
  listNeedsVerification = ref(false),
  baseline = ref('')
const resetVisible = ref(false),
  resetTarget = ref<PersonRecord | null>(null),
  resetPasswordValue = ref(''),
  resetPasswordConfirm = ref(''),
  resetError = ref<unknown>(null)
const form = reactive({
  username: '',
  name: '',
  birthDate: '',
  status: 'ACTIVE',
  graduationDate: '' as string | null,
  department: '',
  socialSecurityNumber: '',
  password: '',
  passwordConfirm: '',
})
type PersonField = keyof typeof form
const fieldErrors = reactive<Partial<Record<PersonField, string>>>({})
function clearFieldErrors() {
  for (const field of Object.keys(fieldErrors) as PersonField[]) delete fieldErrors[field]
}
function showFieldErrors(e: unknown) {
  if (!(e instanceof HttpError) || !Array.isArray(e.details)) return
  for (const detail of e.details) {
    if (
      detail &&
      typeof detail === 'object' &&
      'field' in detail &&
      'message' in detail &&
      typeof detail.field === 'string' &&
      typeof detail.message === 'string' &&
      Object.hasOwn(form, detail.field)
    )
      fieldErrors[detail.field as PersonField] = detail.message
  }
}
for (const field of Object.keys(form) as PersonField[])
  watch(
    () => form[field],
    () => {
      delete fieldErrors[field]
      if (!blocked.value) formError.value = null
    },
  )
watch(
  () => form.password,
  () => {
    if (visible.value && !editing.value && form.passwordConfirm)
      void formRef.value?.validateField('passwordConfirm').catch(() => undefined)
  },
)
const dirty = computed(() => visible.value && JSON.stringify(form) !== baseline.value)
const { confirmLeave } = useDirtyGuard(dirty)
const rules: FormRules = {
  username: [
    { required: true, whitespace: true, message: '请输入登录账号', trigger: 'blur' },
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
  password: [
    { required: true, message: '请设置至少 12 个字符的初始密码', trigger: 'blur' },
    {
      validator: (_r, value: string, callback) => {
        if (!value || value.length < 12)
          callback(new Error('密码至少需要 12 个字符'))
        else if (new TextEncoder().encode(value).length > 72)
          callback(new Error('密码不得超过 72 个 UTF-8 字节'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  passwordConfirm: [
    {
      validator: (_r, value: string, callback) =>
        value && value === form.password
          ? callback()
          : callback(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
}
let generation = 0,
  active = true
async function load() {
  if (!active) return
  const token = ++generation
  loading.value = true
  error.value = null
  try {
    const { data } = await (props.kind === 'students'
      ? api.registrar.students(page.value, 20, keyword.value.trim())
      : api.registrar.professors(page.value, 20, keyword.value.trim()))
    if (token === generation) {
      const lastPage = Math.max(1, Math.ceil(data.total / 20))
      if (page.value > lastPage) {
        page.value = lastPage
        await load()
        return
      }
      items.value = data.items
      total.value = data.total
      listNeedsVerification.value = false
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
  clearFieldErrors()
  Object.assign(form, {
    username: person?.username || '',
    name: person?.name || '',
    birthDate: person?.birthDate || '',
    status: person?.status || 'ACTIVE',
    graduationDate: person?.graduationDate || null,
    department: person?.department || '',
    socialSecurityNumber: '',
    password: '',
    passwordConfirm: '',
  })
  baseline.value = JSON.stringify(form)
  visible.value = true
  formRef.value?.clearValidate()
}
async function edit(person: PersonRecord) {
  if (busy.value) return
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
    form.password = ''
    form.passwordConfirm = ''
    editing.value = null
    done?.()
  }
}
async function submit() {
  if (busy.value || blocked.value || listNeedsVerification.value) return
  clearFieldErrors()
  if (!(await formRef.value?.validate().catch(() => false))) return
  const data: Record<string, unknown> = {
    name: form.name.trim(),
    birthDate: form.birthDate,
    status: form.status,
  }
  if (!editing.value) data.username = form.username.trim()
  if (!editing.value) data.password = form.password
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
    form.password = ''
    form.passwordConfirm = ''
    await load()
  } catch (e) {
    if (!active) return
    formError.value = e
    showFieldErrors(e)
    if (e instanceof HttpError && [0, 412].includes(e.status)) blocked.value = true
  } finally {
    busy.value = false
  }
}
function clearPasswordReset() {
  resetVisible.value = false
  resetTarget.value = null
  resetPasswordValue.value = ''
  resetPasswordConfirm.value = ''
  resetError.value = null
}
function openPasswordReset(person: PersonRecord) {
  if (busy.value || listNeedsVerification.value) return
  resetTarget.value = person
  resetPasswordValue.value = ''
  resetPasswordConfirm.value = ''
  resetError.value = null
  resetVisible.value = true
}
async function submitPasswordReset() {
  if (busy.value || listNeedsVerification.value || !resetTarget.value) return
  if (
    resetPasswordValue.value.length < 12 ||
    new TextEncoder().encode(resetPasswordValue.value).length > 72
  ) {
    resetError.value = new Error('密码至少需要 12 个字符，且不超过 72 个 UTF-8 字节')
    return
  }
  if (resetPasswordValue.value !== resetPasswordConfirm.value) {
    resetError.value = new Error('两次输入的密码不一致')
    return
  }
  const target = resetTarget.value
  busy.value = true
  resetError.value = null
  try {
    const result =
      props.kind === 'students'
        ? await api.registrar.resetStudentPassword(
            target.id,
            resetPasswordValue.value,
            target.accountVersion,
          )
        : await api.registrar.resetProfessorPassword(
            target.id,
            resetPasswordValue.value,
            target.accountVersion,
          )
    items.value = items.value.map((person) =>
      person.id === result.data.id ? { ...person, accountVersion: result.data.accountVersion } : person,
    )
    clearPasswordReset()
    ElMessage.success('登录密码已重置，现有会话已失效')
  } catch (e) {
    if (active) {
      resetError.value = e
      if (e instanceof HttpError && [0, 412].includes(e.status)) listNeedsVerification.value = true
    }
  } finally {
    busy.value = false
  }
}
async function remove(person: PersonRecord) {
  if (busy.value || listNeedsVerification.value) return
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
    if (active) {
      error.value = e
      if (e instanceof HttpError && [0, 412].includes(e.status)) listNeedsVerification.value = true
    }
  } finally {
    busy.value = false
  }
}
onMounted(load)
watch(
  () => props.kind,
  () => {
    // The same view is mounted for both registrar routes in some shells. Reset
    // list and editor state before loading the newly selected kind so records
    // from the previous route cannot remain visible during the request.
    generation++
    page.value = 1
    items.value = []
    total.value = 0
    keyword.value = ''
    error.value = null
    listNeedsVerification.value = false
    clearPasswordReset()
    if (visible.value) {
      visible.value = false
      editing.value = null
      form.socialSecurityNumber = ''
      form.password = ''
      form.passwordConfirm = ''
    }
    void load()
  },
)
watch(
  () => form.birthDate,
  () => {
    if (visible.value && props.kind === 'students' && form.graduationDate)
      void formRef.value?.validateField('graduationDate').catch(() => undefined)
  },
)
onUnmounted(() => {
  active = false
  generation++
  form.socialSecurityNumber = ''
  form.password = ''
  form.passwordConfirm = ''
  clearPasswordReset()
  items.value = []
  editing.value = null
  Object.assign(form, {
    username: '',
    name: '',
    birthDate: '',
    status: 'ACTIVE',
    graduationDate: null,
    department: '',
    socialSecurityNumber: '',
    password: '',
    passwordConfirm: '',
  })
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
      :disabled="busy || listNeedsVerification"
      @click="fill(null)"
      >新增{{ noun }}</el-button
    >
  </div>
  <ErrorNotice
    :error="error"
    retry
    @retry="load"
  />
  <el-alert
    v-if="listNeedsVerification"
    title="删除结果或资料版本尚未确认，请重新加载列表并核对后再操作。"
    type="warning"
    :closable="false"
    show-icon
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
          width="210"
          fixed="right"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              :disabled="busy || listNeedsVerification"
              @click="edit(row)"
              >编辑</el-button
            ><el-button
              link
              type="primary"
              :disabled="busy || listNeedsVerification"
              @click="openPasswordReset(row)"
              >重置密码</el-button
            ><el-button
              link
              type="danger"
              :disabled="busy || listNeedsVerification"
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
      scroll-to-error
      @submit.prevent="submit"
      ><el-form-item
        v-if="!editing"
        label="登录账号"
        prop="username"
        :error="fieldErrors.username"
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
      <el-form-item
        v-if="!editing"
        label="初始密码"
        prop="password"
        :error="fieldErrors.password"
      >
        <el-input
          v-model="form.password"
          type="password"
          maxlength="128"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>
      <el-form-item
        v-if="!editing"
        label="确认初始密码"
        prop="passwordConfirm"
        :error="fieldErrors.passwordConfirm"
      >
        <el-input
          v-model="form.passwordConfirm"
          type="password"
          maxlength="128"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>
      <div class="form-grid">
        <el-form-item
          label="姓名"
          prop="name"
          :error="fieldErrors.name"
          ><el-input
            v-model="form.name"
            maxlength="100" /></el-form-item
        ><el-form-item
          label="出生日期"
          prop="birthDate"
          :error="fieldErrors.birthDate"
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
          :error="fieldErrors.status"
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
          :error="fieldErrors.graduationDate"
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
          :error="fieldErrors.department"
          ><el-input
            v-model="form.department"
            maxlength="100"
        /></el-form-item>
      </div>
      <el-form-item
        label="社会安全号码（可空）"
        prop="socialSecurityNumber"
        :error="fieldErrors.socialSecurityNumber"
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
      </el-form
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
  <el-dialog
    v-model="resetVisible"
    title="重置登录密码"
    width="min(460px, 94vw)"
    :close-on-click-modal="false"
    :show-close="!busy"
    @closed="clearPasswordReset"
  >
    <ErrorNotice :error="resetError" />
    <el-form label-position="top" @submit.prevent="submitPasswordReset">
      <el-form-item label="新密码">
        <el-input
          v-model="resetPasswordValue"
          type="password"
          maxlength="128"
          autocomplete="new-password"
          show-password
          :disabled="busy || listNeedsVerification"
        />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="resetPasswordConfirm"
          type="password"
          maxlength="128"
          autocomplete="new-password"
          show-password
          :disabled="busy || listNeedsVerification"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="clearPasswordReset">取消</el-button>
      <el-button
        type="primary"
        :loading="busy"
        :disabled="listNeedsVerification || !resetPasswordValue || !resetPasswordConfirm"
        @click="submitPasswordReset"
        >保存新密码</el-button
      >
    </template>
  </el-dialog>
</template>
