package dev.nik.reconstructor.classfile;

public record ParsedClass(ClassInfo info, String bytecodeText, String pseudoSource) {}
