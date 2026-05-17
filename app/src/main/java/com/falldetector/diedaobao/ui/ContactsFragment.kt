package com.falldetector.diedaobao.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.falldetector.diedaobao.R
import com.falldetector.diedaobao.data.Contact
import com.falldetector.diedaobao.databinding.FragmentContactsBinding
import kotlinx.coroutines.launch

data class PhoneContact(val name: String, val phone: String)

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ContactAdapter
    private val contacts = mutableListOf<Contact>()
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                showPhoneContactsPicker()
            } else {
                Toast.makeText(requireContext(), "需要读取联系人权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ContactAdapter(contacts) { contact -> deleteContact(contact) }
        binding.recyclerContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerContacts.adapter = adapter

        binding.fabAdd.setOnClickListener { showAddOptions() }

        loadContacts()
        updateEmptyState()
    }

    private fun showAddOptions() {
        val options = arrayOf("📱 从手机联系人导入", "✏️ 手动输入")
        AlertDialog.Builder(requireContext())
            .setTitle("添加紧急联系人")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkAndImportFromPhone()
                    1 -> showManualAddDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkAndImportFromPhone() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) 
                == PackageManager.PERMISSION_GRANTED -> {
                showPhoneContactsPicker()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) -> {
                AlertDialog.Builder(requireContext())
                    .setTitle("需要读取联系人权限")
                    .setMessage("跌倒宝需要访问您的手机联系人，以便快速选择紧急联系人。\n\n您的联系人数据仅用于选择，不会上传。")
                    .setPositiveButton("授权") { _, _ ->
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun showPhoneContactsPicker() {
        val phoneContacts = loadPhoneContacts()
        if (phoneContacts.isEmpty()) {
            Toast.makeText(requireContext(), "未找到联系人", Toast.LENGTH_SHORT).show()
            return
        }

        val listView = ListView(requireContext())
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            phoneContacts.map { "${it.name}\n${it.phone}" }
        )
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("选择联系人")
            .setView(listView)
            .setNegativeButton("取消", null)
            .show()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = phoneContacts[position]
            dialog.dismiss()
            showRelationDialog(selected.name, selected.phone)
        }
    }

    private fun loadPhoneContacts(): List<PhoneContact> {
        val result = mutableListOf<PhoneContact>()
        val seen = mutableSetOf<String>()
        
        val cursor = requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                var phone = it.getString(phoneIndex) ?: continue
                
                // 清理手机号
                phone = phone.replace(Regex("[^0-9]"), "")
                if (phone.startsWith("86") && phone.length > 10) {
                    phone = phone.substring(2)
                }
                if (phone.length == 11 && phone.startsWith("1")) {
                    if (!seen.contains(phone)) {
                        seen.add(phone)
                        result.add(PhoneContact(name, phone))
                    }
                }
            }
        }
        return result
    }

    private fun showRelationDialog(name: String, phone: String) {
        val relations = arrayOf("子女", "配偶", "父母", "兄弟姐妹", "其他")
        AlertDialog.Builder(requireContext())
            .setTitle("选择关系")
            .setItems(relations) { _, which ->
                saveContact(name, phone, relations[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManualAddDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_contact, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_contact_name)
        val etPhone = dialogView.findViewById<EditText>(R.id.et_contact_phone)
        val etRelation = dialogView.findViewById<EditText>(R.id.et_contact_relation)

        AlertDialog.Builder(requireContext())
            .setTitle("添加紧急联系人")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                val relation = etRelation.text.toString().trim().ifEmpty { "家人" }
                
                if (validateContact(name, phone)) {
                    saveContact(name, phone, relation)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun validateContact(name: String, phone: String): Boolean {
        if (name.isBlank() || phone.isBlank()) {
            Toast.makeText(requireContext(), "请填写姓名和电话", Toast.LENGTH_SHORT).show()
            return false
        }
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        if (!cleanPhone.matches(Regex("^1[3-9]\\d{9}$"))) {
            Toast.makeText(requireContext(), "请填写正确的手机号", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun saveContact(name: String, phone: String, relation: String) {
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        viewLifecycleOwner.lifecycleScope.launch {
            val app = com.falldetector.diedaobao.FallDetectionApp.instance
            app.repository.contactDao.insert(
                Contact(name = name, phone = cleanPhone, relation = relation)
            )
            Toast.makeText(requireContext(), "已添加 $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadContacts() {
        val app = com.falldetector.diedaobao.FallDetectionApp.instance
        viewLifecycleOwner.lifecycleScope.launch {
            app.repository.contactDao.getAll().collect { list ->
                contacts.clear()
                contacts.addAll(list)
                adapter.notifyDataSetChanged()
                updateEmptyState()
                syncFirstContactToPrefs(list.firstOrNull())
            }
        }
    }

    private fun syncFirstContactToPrefs(contact: Contact?) {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (contact != null) {
            prefs.edit()
                .putString("contact_name", contact.name)
                .putString("contact_phone", contact.phone)
                .apply()
            binding.tvContactHint.text = "紧急联系人：${contact.name} (${contact.phone})"
        } else {
            prefs.edit()
                .remove("contact_name")
                .remove("contact_phone")
                .apply()
            binding.tvContactHint.text = "请添加紧急联系人"
        }
    }

    private fun updateEmptyState() {
        if (contacts.isEmpty()) {
            binding.tvContactHint.text = "请添加紧急联系人"
            binding.tvContactHint.visibility = View.VISIBLE
        } else {
            binding.tvContactHint.text = "已添加 ${contacts.size} 位紧急联系人"
            binding.tvContactHint.visibility = View.VISIBLE
        }
    }

    private fun deleteContact(contact: Contact) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除联系人")
            .setMessage("确定删除 ${contact.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = com.falldetector.diedaobao.FallDetectionApp.instance
                    app.repository.contactDao.delete(contact)
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ContactAdapter(
    private val contacts: List<Contact>,
    private val onDelete: (Contact) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

    class ViewHolder(val view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.view.findViewById<android.widget.TextView>(R.id.tv_contact_name).text = "${contact.name}（${contact.relation}）"
        holder.view.findViewById<android.widget.TextView>(R.id.tv_contact_phone).text = contact.phone
        holder.view.findViewById<android.widget.TextView>(R.id.tv_contact_priority).text = when (position) {
            0 -> "第一联系人"
            1 -> "第二联系人"
            else -> "第三联系人"
        }
        holder.view.setOnLongClickListener {
            onDelete(contact)
            true
        }
    }

    override fun getItemCount() = contacts.size
}
