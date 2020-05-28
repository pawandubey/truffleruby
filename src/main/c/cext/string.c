#include <truffleruby-impl.h>
#include <ruby/encoding.h>

// String, rb_str_*

char* ruby_strdup(const char *str) {
  char *tmp;
  size_t len = strlen(str) + 1;

  tmp = xmalloc(len);
  memcpy(tmp, str, len);

  return tmp;
}

VALUE rb_string_value(VALUE *value_pointer) {
  return rb_tr_string_value(value_pointer);
}

char *rb_string_value_ptr(VALUE *value_pointer) {
  return rb_tr_string_value_ptr(value_pointer);
}

char *rb_string_value_cstr(VALUE *value_pointer) {
  return rb_tr_string_value_cstr(value_pointer);
}

char *RSTRING_PTR_IMPL(VALUE string) {
  return NATIVE_RSTRING_PTR(string);
}

char *RSTRING_END_IMPL(VALUE string) {
  return NATIVE_RSTRING_PTR(string) + RSTRING_LEN(string);
}

int MBCLEN_NEEDMORE_P(int r) {
  return polyglot_as_i32(polyglot_invoke(RUBY_CEXT, "MBCLEN_NEEDMORE_P", r));
}

int MBCLEN_NEEDMORE_LEN(int r) {
  return polyglot_as_i32(polyglot_invoke(RUBY_CEXT, "MBCLEN_NEEDMORE_LEN", r));
}

int MBCLEN_CHARFOUND_P(int r) {
  return polyglot_as_boolean(polyglot_invoke(RUBY_CEXT, "MBCLEN_CHARFOUND_P", r));
}

int MBCLEN_CHARFOUND_LEN(int r) {
  return polyglot_as_i32(polyglot_invoke(RUBY_CEXT, "MBCLEN_CHARFOUND_LEN", r));
}

int rb_str_len(VALUE string) {
  return polyglot_as_i32(polyglot_invoke(rb_tr_unwrap(string), "bytesize"));
}

VALUE rb_str_new(const char *string, long length) {
  if (length < 0) {
    rb_raise(rb_eArgError, "negative string size (or size too big)");
  }

  if (string == NULL) {
    return rb_tr_wrap(polyglot_invoke(RUBY_CEXT, "rb_str_new_nul", length));
  } else {
    return rb_tr_wrap(polyglot_invoke(RUBY_CEXT, "rb_str_new_native", string, length));
  }
}

VALUE rb_tainted_str_new(const char *ptr, long len) {
    VALUE str = rb_str_new(ptr, len);

    OBJ_TAINT(str);
    return str;
}

VALUE rb_str_new_cstr(const char *string) {
  // TODO CS 24-Oct-17 would be nice to read in one go rather than strlen followed by read
  size_t len = strlen(string);
  return rb_str_new(string, len);
}

VALUE rb_str_new_shared(VALUE string) {
  return RUBY_INVOKE(string, "dup");
}

VALUE rb_str_new_with_class(VALUE str, const char *string, long len) {
  return RUBY_INVOKE(RUBY_INVOKE(str, "class"), "new", rb_str_new(string, len));
}

VALUE rb_tainted_str_new_cstr(const char *ptr) {
    VALUE str = rb_str_new_cstr(ptr);

    OBJ_TAINT(str);
    return str;
}

ID rb_intern_str(VALUE string) {
  return RUBY_CEXT_INVOKE("rb_intern_str", string);
}

VALUE rb_str_cat(VALUE string, const char *to_concat, long length) {
  if (length == 0) {
    return string;
  }
  if (length < 0) {
    rb_raise(rb_eArgError, "negative string size (or size too big)");
  }
  int old_length = RSTRING_LEN(string);
  rb_str_resize(string, old_length + length);
  // Resizing the string will clear out the code range, so there is no
  // need to do it explicitly.
  memcpy(RSTRING_PTR(string) + old_length, to_concat, length);
  return string;
}

#undef rb_str_cat_cstr
VALUE rb_str_cat_cstr(VALUE string, const char *to_concat) {
  return rb_str_cat(string, to_concat, strlen(to_concat));
}

VALUE rb_str_to_str(VALUE string) {
  return rb_convert_type(string, T_STRING, "String", "to_str");
}

VALUE rb_fstring(VALUE str) {
  return RUBY_INVOKE(str, "-@");
}

VALUE rb_str_buf_new(long capacity) {
  VALUE str = rb_str_new(NULL, capacity);
  rb_str_set_len(str, 0);
  return str;
}

VALUE rb_str_append(VALUE string, VALUE to_append) {
  return RUBY_CEXT_INVOKE("rb_str_append", string, to_append);
}

VALUE rb_str_concat(VALUE string, VALUE to_concat) {
  return RUBY_CEXT_INVOKE("rb_str_concat", string, to_concat);
}

void rb_str_set_len(VALUE string, long length) {
  long capacity = rb_str_capacity(string);
  if (length > capacity || length < 0) {
    rb_raise(rb_eRuntimeError, "probable buffer overflow: %ld for %ld", length, capacity);
  }
  rb_tr_wrap(polyglot_invoke(RUBY_CEXT, "rb_str_set_len", rb_tr_unwrap(string), length));
}

VALUE rb_str_new_frozen(VALUE value) {
  return RUBY_CEXT_INVOKE("rb_str_new_frozen", value);
}

VALUE rb_String(VALUE value) {
  return RUBY_CEXT_INVOKE("rb_String", value);
}

VALUE rb_str_resize(VALUE string, long length) {
  return rb_tr_wrap(polyglot_invoke(RUBY_CEXT, "rb_str_resize", rb_tr_unwrap(string), length));
}

VALUE rb_str_split(VALUE string, const char *split) {
  return RUBY_INVOKE(string, "split", rb_str_new_cstr(split));
}

void rb_str_modify(VALUE string) {
  ENC_CODERANGE_CLEAR(string);
}

VALUE rb_str_buf_new_cstr(const char *string) {
  return rb_str_new_cstr(string);
}

int rb_str_cmp(VALUE a, VALUE b) {
  return polyglot_as_i32(RUBY_INVOKE_NO_WRAP(a, "<=>", b));
}

VALUE rb_str_buf_cat(VALUE string, const char *to_concat, long length) {
  return rb_str_cat(string, to_concat, length);
}

VALUE rb_str_conv_enc(VALUE string, rb_encoding *from, rb_encoding *to) {
  return rb_str_conv_enc_opts(string, from, to, 0, Qnil);
}

VALUE rb_str_conv_enc_opts(VALUE str, rb_encoding *from, rb_encoding *to, int ecflags, VALUE ecopts) {
  if (!to) return str;
  if (!from) from = rb_enc_get(str);
  if (from == to) return str;
  return rb_tr_wrap(polyglot_invoke(RUBY_CEXT, "rb_str_conv_enc_opts", rb_tr_unwrap(str), rb_tr_unwrap(rb_enc_from_encoding(from)), rb_tr_unwrap(rb_enc_from_encoding(to)), ecflags, rb_tr_unwrap(ecopts)));
}

VALUE
rb_tainted_str_new_with_enc(const char *ptr, long len, rb_encoding *enc) {
  VALUE str = rb_enc_str_new(ptr, len, enc);
  OBJ_TAINT(str);
  return str;
}

VALUE rb_external_str_new_with_enc(const char *ptr, long len, rb_encoding *eenc) {
  VALUE str;
  str = rb_tainted_str_new_with_enc(ptr, len, eenc);
  str = rb_external_str_with_enc(str, eenc);
  return str;
}

VALUE rb_external_str_with_enc(VALUE str, rb_encoding *eenc) {
  if (polyglot_as_boolean(RUBY_INVOKE_NO_WRAP(rb_enc_from_encoding(eenc), "==", rb_enc_from_encoding(rb_usascii_encoding()))) &&
    rb_enc_str_coderange(str) != ENC_CODERANGE_7BIT) {
    rb_enc_associate_index(str, rb_ascii8bit_encindex());
    return str;
  }
  rb_enc_associate(str, eenc);
  return rb_str_conv_enc(str, eenc, rb_default_internal_encoding());
}

VALUE rb_external_str_new(const char *string, long len) {
  return rb_external_str_new_with_enc(string, len, rb_default_external_encoding());
}

VALUE rb_external_str_new_cstr(const char *string) {
  return rb_external_str_new_with_enc(string, strlen(string), rb_default_external_encoding());
}

VALUE rb_locale_str_new(const char *string, long len) {
  return rb_external_str_new_with_enc(string, len, rb_locale_encoding());
}

VALUE rb_locale_str_new_cstr(const char *string) {
  return rb_external_str_new_with_enc(string, strlen(string), rb_locale_encoding());
}

VALUE rb_filesystem_str_new(const char *string, long len) {
  return rb_external_str_new_with_enc(string, len, rb_filesystem_encoding());
}

VALUE rb_filesystem_str_new_cstr(const char *string) {
  return rb_external_str_new_with_enc(string, strlen(string), rb_filesystem_encoding());
}

VALUE rb_str_export(VALUE string) {
  return rb_str_conv_enc(string, STR_ENC_GET(string), rb_default_external_encoding());
}

VALUE rb_str_export_locale(VALUE string) {
  return rb_str_conv_enc(string, STR_ENC_GET(string), rb_locale_encoding());
}

VALUE rb_str_export_to_enc(VALUE string, rb_encoding *enc) {
  return rb_str_conv_enc(string, STR_ENC_GET(string), enc);
}

VALUE rb_str_intern(VALUE string) {
  return RUBY_INVOKE(string, "intern");
}

VALUE rb_str_length(VALUE string) {
  return RUBY_INVOKE(string, "length");
}

VALUE rb_str_plus(VALUE a, VALUE b) {
  return RUBY_INVOKE(a, "+", b);
}

VALUE rb_str_subseq(VALUE string, long beg, long len) {
    return rb_tr_wrap(polyglot_invoke(rb_tr_unwrap(string), "byteslice", beg, len));
}

VALUE rb_str_substr(VALUE string, long beg, long len) {
  return rb_tr_wrap(polyglot_invoke(rb_tr_unwrap(string), "[]", beg, len));
}

st_index_t rb_str_hash(VALUE string) {
  return (st_index_t) polyglot_as_i64(polyglot_invoke(rb_tr_unwrap(string), "hash"));
}

void rb_str_update(VALUE string, long beg, long len, VALUE value) {
  polyglot_invoke(rb_tr_unwrap(string), "[]=", beg, len, rb_tr_unwrap(value));
}

VALUE rb_str_replace(VALUE str, VALUE by) {
  return RUBY_INVOKE(str, "replace", by);
}

VALUE rb_str_equal(VALUE a, VALUE b) {
  return RUBY_INVOKE(a, "==", b);
}

void rb_str_free(VALUE string) {
  //  intentional noop here
}

VALUE rb_str_encode(VALUE str, VALUE to, int ecflags, VALUE ecopts) {
  return rb_tr_wrap(polyglot_invoke(RUBY_CEXT, "rb_str_encode", rb_tr_unwrap(str), rb_tr_unwrap(to), ecflags, rb_tr_unwrap(ecopts)));
}

VALUE rb_usascii_str_new(const char *ptr, long len) {
  return RUBY_INVOKE(rb_str_new(ptr, len), "force_encoding", rb_enc_from_encoding(rb_usascii_encoding()));
}

VALUE rb_usascii_str_new_cstr(const char *ptr) {
  return RUBY_INVOKE(rb_str_new_cstr(ptr), "force_encoding", rb_enc_from_encoding(rb_usascii_encoding()));
}

VALUE rb_str_times(VALUE string, VALUE times) {
  return RUBY_INVOKE(string, "*", times);
}

VALUE rb_str_tmp_new(long len) {
  return rb_obj_hide(rb_str_new(NULL, len));
}

#undef rb_utf8_str_new
VALUE rb_utf8_str_new(const char *ptr, long len) {
  return rb_enc_str_new(ptr, len, rb_utf8_encoding());
}

#undef rb_utf8_str_new_cstr
VALUE rb_utf8_str_new_cstr(const char *ptr) {
  // TODO CS 11-Oct-19 would be nice to read in one go rather than strlen followed by read
  return rb_utf8_str_new(ptr, strlen(ptr));
}

VALUE rb_utf8_str_new_static(const char *ptr, long len) {
  return rb_utf8_str_new(ptr, len);
}

void rb_str_modify_expand(VALUE str, long expand) {
  long len = RSTRING_LEN(str);
  if (expand < 0) {
    rb_raise(rb_eArgError, "negative expanding string size");
  }
  if (expand > INT_MAX - len) {
    rb_raise(rb_eArgError, "string size too big");
  }

  if (expand > 0) {
    // rb_str_modify_expand() resizes the native buffer but does not change
    // RSTRING_LEN() (and therefore String#bytesize).
    // TODO (eregon, 26 Apr 2018): Do this more directly.
    rb_str_resize(str, len + expand);
    rb_str_set_len(str, len);
  }

  ENC_CODERANGE_CLEAR(str);
}

VALUE rb_str_drop_bytes(VALUE str, long len) {
  long olen = RSTRING_LEN(str);
  if (len > olen) {
    len = olen;
  }
  return rb_str_replace(str, rb_str_subseq(str, len, olen - len));
}

size_t rb_str_capacity(VALUE str) {
  return polyglot_as_i64(RUBY_CEXT_INVOKE_NO_WRAP("rb_str_capacity", str));
}
