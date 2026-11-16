#!/usr/bin/env python3
"""Tests-first unit coverage for the JNI contract verifier itself."""
import importlib.util
import pathlib
import struct
import unittest

HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('verify_native_jni_contract',HERE/'verify_native_jni_contract.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class NativeContractVerifierTest(unittest.TestCase):
    def test_required_table_has_nine_distinct_methods(self):
        self.assertEqual(9,len(module.REQUIRED))
    def test_registration_parser_matches_cpp(self):
        cpp=(HERE.parent/'app/src/main/cpp/native-lib.cpp').read_text(encoding='utf-8')
        self.assertEqual(module.REQUIRED,module.registrations(cpp))
    def test_detects_wrong_elf_header_and_architecture(self):
        self.assertTrue(module.check_elf_arm64(b'not ELF'))
        b=bytearray(64);b[:6]=b'\x7fELF\x02\x01';struct.pack_into('<HH',b,16,3,62)
        self.assertTrue(module.check_elf_arm64(bytes(b)))
        struct.pack_into('<HH',b,16,3,183)
        self.assertEqual([],module.check_elf_arm64(bytes(b)))
    def test_missing_backend_binding_is_detected_in_synthetic_registration_names(self):
        # Test data only; not an artificial AAR or a packaged substitute for any real .so.
        blob=b''.join(name.encode('ascii') + b'\x00' for name in module.REQUIRED if name != 'nativeBackendForContext')
        self.assertEqual(['nativeBackendForContext'],module.missing_registration_names(blob,module.REQUIRED))
    def test_complete_registration_names_are_accepted(self):
        blob=b''.join(name.encode('ascii') + b'\x00' for name in module.REQUIRED)
        self.assertEqual([],module.missing_registration_names(blob,module.REQUIRED))

if __name__=='__main__':unittest.main()
