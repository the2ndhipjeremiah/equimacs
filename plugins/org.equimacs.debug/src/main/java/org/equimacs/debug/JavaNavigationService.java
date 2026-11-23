package org.equimacs.debug;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jface.text.Document;

/**
 * Headless JDT code-navigation operations (references, call hierarchy, type hierarchy).
 */
final class JavaNavigationService {

    private static final int MAX_DEPTH = 5;

    Map<String, Object> findReferences(String filePath, int offset) throws CoreException {
        ICompilationUnit unit = requireCompilationUnit(filePath);
        IJavaElement target = selectSymbol(unit, offset, filePath);
        return findReferencesFor(target);
    }

    Map<String, Object> callHierarchy(
            String filePath, int offset, String direction, int depth) throws CoreException {
        ICompilationUnit unit = requireCompilationUnit(filePath);
        IJavaElement selected = selectSymbol(unit, offset, filePath);
        IMethod method = resolveMethod(selected);
        if (method == null) {
            throw error("Selected symbol is not a method: " + selected.getElementName());
        }
        return callHierarchyFor(method, direction, depth);
    }

    Map<String, Object> typeHierarchy(String filePath, int offset) throws CoreException {
        ICompilationUnit unit = requireCompilationUnit(filePath);
        IJavaElement selected = selectSymbol(unit, offset, filePath);
        IType type = resolveType(selected, unit);
        if (type == null) {
            throw error("No Java type found at offset " + offset + " in " + filePath);
        }
        return typeHierarchyFor(type);
    }

    Map<String, Object> inspectSymbolData(String filePath, int offset) throws CoreException {
        ICompilationUnit unit = requireCompilationUnit(filePath);
        IJavaElement selected = selectSymbol(unit, offset, filePath);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("target", describeSymbolTarget(selected, offset));

        IType type = resolveType(selected, unit);
        if (type != null) {
            data.put("members", extractMembers(type));
            try {
                data.put("hierarchy", typeHierarchyFor(type));
            } catch (Exception ignored) {}
        }

        try {
            Map<String, Object> refsResult = findReferencesFor(selected);
            data.put("references", refsResult.get("references"));
        } catch (Exception ignored) {}

        IMethod method = resolveMethod(selected);
        if (method != null) {
            try {
                data.put("callers", callHierarchyFor(method, "callers", 2).get("nodes"));
            } catch (Exception ignored) {}
            try {
                data.put("callees", callHierarchyFor(method, "callees", 2).get("nodes"));
            } catch (Exception ignored) {}
        }
        return data;
    }

    private Map<String, Object> findReferencesFor(IJavaElement target) throws CoreException {
        List<Map<String, Object>> references = new ArrayList<>();
        SearchPattern pattern = SearchPattern.createPattern(target, IJavaSearchConstants.REFERENCES);
        if (pattern != null) {
            IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
            SearchEngine engine = new SearchEngine();
            engine.search(
                pattern,
                new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) throws CoreException {
                        Object element = match.getElement();
                        IResource resource = match.getResource();
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("file", resource != null ? resource.getFullPath().toString() : null);
                        item.put("offset", match.getOffset());
                        item.put("length", match.getLength());
                        int line = lineAtOffset(resource, match.getOffset());
                        item.put("line", line);
                        item.put("snippet", lineSnippet(resource, line));
                        if (element instanceof IJavaElement javaEl) {
                            item.put("enclosingElement", describeElement(javaEl));
                        }
                        references.add(item);
                    }
                },
                new NullProgressMonitor());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", describeElement(target));
        result.put("count", references.size());
        result.put("references", references);
        return result;
    }

    private Map<String, Object> callHierarchyFor(
            IMethod method, String direction, int depth) throws CoreException {
        String dir = (direction == null || direction.isBlank())
            ? "callers"
            : direction.trim().toLowerCase();
        if (!"callers".equals(dir) && !"callees".equals(dir)) {
            throw error("Unsupported call hierarchy direction: " + direction + " (expected callers or callees)");
        }
        int clampedDepth = Math.max(1, Math.min(depth <= 0 ? 1 : depth, MAX_DEPTH));

        Set<String> visited = new HashSet<>();
        visited.add(method.getHandleIdentifier());
        List<Map<String, Object>> nodes = "callers".equals(dir)
            ? collectCallers(method, clampedDepth, visited)
            : collectCallees(method, clampedDepth, visited);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", describeElement(method));
        result.put("direction", dir);
        result.put("depth", clampedDepth);
        result.put("nodes", nodes);
        return result;
    }

    private Map<String, Object> typeHierarchyFor(IType type) throws JavaModelException {
        ITypeHierarchy hierarchy = type.newTypeHierarchy(new NullProgressMonitor());
        IType superclass = hierarchy.getSuperclass(type);
        IType[] allSupers = hierarchy.getAllSuperclasses(type);
        IType[] interfaces = hierarchy.getAllInterfaces();
        IType[] subtypes = hierarchy.getSubtypes(type);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", describeType(type));
        result.put("superclass", superclass != null ? describeType(superclass) : null);
        result.put("superclasses", describeTypes(allSupers));
        result.put("interfaces", describeTypes(interfaces));
        result.put("subtypes", describeTypes(subtypes));
        return result;
    }

    private static Map<String, Object> describeSymbolTarget(IJavaElement element, int fallbackOffset) {
        Map<String, Object> info = new LinkedHashMap<>(describeElement(element));
        if (element instanceof IType type) {
            info.put("fqn", type.getFullyQualifiedName());
            info.put("signature", typeSignatureLabel(type));
        } else if (element instanceof IMember member) {
            IType declaring = member.getDeclaringType();
            if (declaring != null) {
                String declaringFqn = declaring.getFullyQualifiedName();
                info.put("declaringType", declaringFqn);
                info.put("fqn", declaringFqn + "#" + member.getElementName());
            }
            if (element instanceof IMethod method) {
                info.put("signature", methodSignatureLabel(method));
            } else if (element instanceof IField field) {
                info.put("signature", fieldSignatureLabel(field));
            }
        } else if (element instanceof ILocalVariable local) {
            info.put("signature", localSignatureLabel(local));
        }

        IResource resource = element.getResource();
        int symOffset = fallbackOffset;
        int line = -1;
        if (element instanceof ISourceReference sourceRef) {
            try {
                ISourceRange range = sourceRef.getNameRange();
                if (range == null || range.getOffset() < 0) {
                    range = sourceRef.getSourceRange();
                }
                if (range != null && range.getOffset() >= 0) {
                    symOffset = range.getOffset();
                    line = lineAtOffset(resource, symOffset);
                }
            } catch (JavaModelException ignored) {}
        }
        info.put("offset", symOffset);
        info.put("line", line);
        return info;
    }

    private static List<Map<String, Object>> extractMembers(IType type) throws JavaModelException {
        List<Map<String, Object>> members = new ArrayList<>();
        IResource typeRes = type.getResource();
        String typeFile = typeRes != null ? typeRes.getFullPath().toString() : null;
        Document typeDoc = readDocument(typeRes);

        for (IField field : type.getFields()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "field");
            item.put("name", field.getElementName());
            item.put("signature", fieldSignatureLabel(field));
            IResource res = field.getResource() != null ? field.getResource() : typeRes;
            item.put("file", res != null ? res.getFullPath().toString() : typeFile);
            ISourceRange range = field.getNameRange();
            Document doc = (res == typeRes) ? typeDoc : readDocument(res);
            item.put("line", range != null ? lineAtOffset(doc, range.getOffset()) : -1);
            members.add(item);
        }

        for (IMethod method : type.getMethods()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", method.isConstructor() ? "constructor" : "method");
            item.put("name", method.getElementName());
            item.put("signature", methodSignatureLabel(method));
            IResource res = method.getResource() != null ? method.getResource() : typeRes;
            item.put("file", res != null ? res.getFullPath().toString() : typeFile);
            ISourceRange range = method.getNameRange();
            Document doc = (res == typeRes) ? typeDoc : readDocument(res);
            item.put("line", range != null ? lineAtOffset(doc, range.getOffset()) : -1);
            members.add(item);
        }
        return members;
    }

    private static String typeSignatureLabel(IType type) {
        try {
            if (type.isInterface()) {
                return "interface";
            }
            if (type.isEnum()) {
                return "enum";
            }
            if (type.isRecord()) {
                return "record";
            }
            return "class";
        } catch (JavaModelException ignored) {
            return "type";
        }
    }

    private static String methodSignatureLabel(IMethod method) {
        try {
            return Signature.toString(
                method.getSignature(),
                method.getElementName(),
                method.getParameterNames(),
                false,
                !method.isConstructor());
        } catch (Exception ignored) {
            try {
                return method.getSignature();
            } catch (JavaModelException ex) {
                return method.getElementName();
            }
        }
    }

    private static String fieldSignatureLabel(IField field) {
        try {
            return Signature.toString(field.getTypeSignature());
        } catch (Exception ignored) {
            try {
                return field.getTypeSignature();
            } catch (JavaModelException ex) {
                return "";
            }
        }
    }

    private static String localSignatureLabel(ILocalVariable local) {
        try {
            return Signature.toString(local.getTypeSignature());
        } catch (Exception ignored) {
            return local.getTypeSignature();
        }
    }

    private List<Map<String, Object>> collectCallers(
            IMethod target, int remainingDepth, Set<String> visited) throws CoreException {
        if (remainingDepth <= 0) {
            return List.of();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        SearchPattern pattern = SearchPattern.createPattern(target, IJavaSearchConstants.REFERENCES);
        if (pattern == null) {
            return nodes;
        }
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        SearchEngine engine = new SearchEngine();
        List<SearchMatch> matches = new ArrayList<>();
        engine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            scope,
            new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    matches.add(match);
                }
            },
            new NullProgressMonitor());

        for (SearchMatch match : matches) {
            if (!(match.getElement() instanceof IMethod caller)) {
                continue;
            }
            IResource resource = match.getResource();
            int line = lineAtOffset(resource, match.getOffset());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("element", describeElement(caller));
            node.put("file", resource != null ? resource.getFullPath().toString() : null);
            node.put("line", line);
            node.put("offset", match.getOffset());
            node.put("snippet", lineSnippet(resource, line));
            if (remainingDepth > 1 && visited.add(caller.getHandleIdentifier())) {
                node.put("children", collectCallers(caller, remainingDepth - 1, visited));
            } else {
                node.put("children", List.of());
            }
            nodes.add(node);
        }
        return nodes;
    }

    private List<Map<String, Object>> collectCallees(
            IMethod target, int remainingDepth, Set<String> visited) throws CoreException {
        if (remainingDepth <= 0) {
            return List.of();
        }
        ICompilationUnit unit = target.getCompilationUnit();
        if (unit == null) {
            return List.of();
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        ASTNode rootNode = parser.createAST(new NullProgressMonitor());
        if (!(rootNode instanceof CompilationUnit astRoot)) {
            return List.of();
        }

        ISourceRange targetRange = target.getSourceRange();
        List<Map<String, Object>> nodes = new ArrayList<>();
        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                int start = node.getStartPosition();
                int end = start + node.getLength();
                return start <= targetRange.getOffset()
                    && end >= targetRange.getOffset() + targetRange.getLength();
            }

            @Override
            public boolean visit(MethodInvocation node) {
                addCallee(node.resolveMethodBinding(), node.getStartPosition());
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                addCallee(node.resolveMethodBinding(), node.getStartPosition());
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                addCallee(node.resolveConstructorBinding(), node.getStartPosition());
                return true;
            }

            private void addCallee(IMethodBinding binding, int callOffset) {
                if (binding == null) {
                    return;
                }
                IJavaElement resolved = binding.getJavaElement();
                if (!(resolved instanceof IMethod callee)) {
                    return;
                }
                IResource resource = unit.getResource();
                int line = astRoot.getLineNumber(callOffset);
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("element", describeElement(callee));
                node.put("file", resource != null ? resource.getFullPath().toString() : null);
                node.put("line", line);
                node.put("offset", callOffset);
                node.put("snippet", lineSnippet(resource, line));
                try {
                    if (remainingDepth > 1 && visited.add(callee.getHandleIdentifier())) {
                        node.put("children", collectCallees(callee, remainingDepth - 1, visited));
                    } else {
                        node.put("children", List.of());
                    }
                } catch (CoreException e) {
                    node.put("children", List.of());
                }
                nodes.add(node);
            }
        });
        return nodes;
    }

    private ICompilationUnit requireCompilationUnit(String filePath) throws CoreException {
        IResource resource = resolveResource(filePath);
        if (resource == null) {
            throw error("Resource not found: " + filePath);
        }
        if (!(resource instanceof IFile file)) {
            throw error("Target is not a file: " + filePath);
        }
        IJavaElement javaElement = JavaCore.create(file);
        if (!(javaElement instanceof ICompilationUnit unit)) {
            throw error("Target is not a Java compilation unit: " + filePath);
        }
        return unit;
    }

    private IJavaElement selectSymbol(ICompilationUnit unit, int offset, String filePath) throws CoreException {
        IJavaElement[] selected = unit.codeSelect(offset, 0);
        if (selected.length == 0) {
            selected = unit.codeSelect(offset, 1);
        }
        if (selected.length == 0) {
            throw error("No Java symbol found at offset " + offset + " in " + filePath);
        }
        return selected[0];
    }

    private static IMethod resolveMethod(IJavaElement element) {
        if (element instanceof IMethod m) {
            return m;
        }
        IJavaElement ancestor = element.getAncestor(IJavaElement.METHOD);
        return ancestor instanceof IMethod m ? m : null;
    }

    private static IType resolveType(IJavaElement element, ICompilationUnit unit) {
        if (element instanceof IType t) {
            return t;
        }
        IJavaElement ancestor = element.getAncestor(IJavaElement.TYPE);
        if (ancestor instanceof IType t) {
            return t;
        }
        return unit.findPrimaryType();
    }

    private static Map<String, Object> describeElement(IJavaElement element) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", element.getElementName());
        info.put("kind", elementKindName(element.getElementType()));
        IResource resource = element.getResource();
        info.put("file", resource != null ? resource.getFullPath().toString() : null);
        if (element instanceof IMethod method) {
            IType declaring = method.getDeclaringType();
            info.put("declaringType", declaring != null ? declaring.getFullyQualifiedName() : null);
        }
        return info;
    }

    private static List<Map<String, Object>> describeTypes(IType[] types) throws JavaModelException {
        List<Map<String, Object>> list = new ArrayList<>();
        if (types != null) {
            for (IType t : types) {
                list.add(describeType(t));
            }
        }
        return list;
    }

    private static Map<String, Object> describeType(IType type) throws JavaModelException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", type.getElementName());
        info.put("fqn", type.getFullyQualifiedName());
        info.put("isInterface", type.isInterface());
        IResource resource = type.getResource();
        info.put("file", resource != null ? resource.getFullPath().toString() : null);
        if (resource != null && type.getNameRange() != null) {
            info.put("line", lineAtOffset(resource, type.getNameRange().getOffset()));
        } else {
            info.put("line", -1);
        }
        return info;
    }

    private static String elementKindName(int type) {
        return switch (type) {
            case IJavaElement.TYPE -> "type";
            case IJavaElement.METHOD -> "method";
            case IJavaElement.FIELD -> "field";
            case IJavaElement.LOCAL_VARIABLE -> "localVariable";
            case IJavaElement.COMPILATION_UNIT -> "compilationUnit";
            case IJavaElement.PACKAGE_FRAGMENT -> "package";
            default -> "element(" + type + ")";
        };
    }

    private static Document readDocument(IResource resource) {
        if (!(resource instanceof IFile file)) {
            return null;
        }
        try {
            return new Document(file.readString());
        } catch (Exception e) {
            return null;
        }
    }

    private static int lineAtOffset(Document doc, int offset) {
        if (doc == null || offset < 0) {
            return -1;
        }
        try {
            return doc.getLineOfOffset(Math.min(offset, doc.getLength())) + 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int lineAtOffset(IResource resource, int offset) {
        return lineAtOffset(readDocument(resource), offset);
    }

    private static String lineSnippet(IResource resource, int line) {
        if (!(resource instanceof IFile file) || line <= 0) {
            return null;
        }
        try {
            String content = file.readString();
            Document doc = new Document(content);
            int idx = line - 1;
            if (idx >= doc.getNumberOfLines()) {
                return null;
            }
            int offset = doc.getLineOffset(idx);
            int length = doc.getLineLength(idx);
            return doc.get(offset, length).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static IResource resolveResource(String pathStr) {
        Path path = new Path(pathStr);
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
        if (resource != null) {
            return resource;
        }
        java.net.URI uri;
        try {
            uri = java.nio.file.Path.of(pathStr).toUri();
        } catch (java.nio.file.InvalidPathException e) {
            uri = java.net.URI.create("file:///" + pathStr.replace("\\", "/").replace(" ", "%20"));
        }
        IResource[] resources = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(uri);
        return resources.length > 0 ? resources[0] : null;
    }

    private static CoreException error(String message) {
        return new CoreException(new Status(IStatus.ERROR, "org.equimacs.debug", message));
    }
}
