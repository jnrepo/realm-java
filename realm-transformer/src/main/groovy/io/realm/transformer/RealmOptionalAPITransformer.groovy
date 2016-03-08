package io.realm.transformer

import com.android.SdkConstants
import com.android.build.api.transform.Format
import groovy.io.FileType
import io.realm.annotations.internal.OptionalAPI

import com.android.build.api.transform.Context
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformOutputProvider
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import javassist.ClassPool
import javassist.CtMethod
import javassist.LoaderClassPath
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.jar.JarFile
import java.util.regex.Pattern

import static com.android.build.api.transform.QualifiedContent.*

class RealmOptionalAPITransformer extends Transform {

    private Logger logger = LoggerFactory.getLogger('realm-logger')

    @Override
    String getName() {
        return "RealmOptionalAPITransformer"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return ImmutableSet.<ContentType> of(DefaultContentType.CLASSES)
    }

    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return Sets.immutableEnumSet(Scope.EXTERNAL_LIBRARIES)
    }

    @Override
    Set<Scope> getReferencedScopes() {
        return Sets.immutableEnumSet(Scope.PROJECT, Scope.PROJECT_LOCAL_DEPS, Scope.SUB_PROJECTS,
                Scope.SUB_PROJECTS_LOCAL_DEPS, Scope.EXTERNAL_LIBRARIES)
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(Context context,
                   Collection<TransformInput> inputs, Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider, boolean isIncremental)
            throws IOException, TransformException, InterruptedException {
        logger.info "RealmOptionalAPITransformer started."

        // Find all the class names
        def classNames = getClassNames(inputs)
        def refClassNames = getClassNames(referencedInputs)
        // Create and populate the Javassist class pool
        def classPool = createClassPool(inputs)

        def pkgNameQuote = Pattern.quote("io.realm")
        def pattern = Pattern.compile("^$pkgNameQuote\\.[^\\.]*\$")
        logger.info "$pattern"
        classNames.findAll {
            if (it.matches(pattern)) {
                logger.info "getPackageMethods $it"
                return true
            }
            return false
        }.each {
            classPool.get(it).getDeclaredMethods().each {
                def optionalAPIAnnotation = (OptionalAPI)it.getAnnotation(OptionalAPI.class)
                if (optionalAPIAnnotation == null) {
                    logger.info "$it.declaringClass.name $it.name doesn't have @OptionalAPI annotation"
                } else if (optionalAPIAnnotation.dependencies().size() == 0) {
                    throw new RuntimeException("$it.name doesn't have proper dependencies.")
                } else if (!refClassNames.containsAll(optionalAPIAnnotation.dependencies())) {
                    // Doesn't have enough dependencies, remove the API
                    logger.info "RealmOptionalAPITransformer found $it.declaringClass.name $it.name with annotation. has been removed."
                    it.declaringClass.removeMethod(it)
                } else {
                    logger.info "$it.declaringClass.name $it.name has all dependencies" + optionalAPIAnnotation.dependencies()
                }
            }
        }

        // Create outputs
        classNames.each {
            logger.info "  Modifying class ${it}"
            def ctClass = classPool.getCtClass(it)
            ctClass.writeFile(outputProvider.getContentLocation(
                    'realm-optional-api', getInputTypes(), getScopes(), Format.DIRECTORY).canonicalPath)
        }
        logger.info "RealmOptionalAPITransformer end."
    }

    private Set<String> getClassNames(Collection<TransformInput> inputs) {
        Set<String> classNames = new HashSet<String>()

        logger.info "getClassNames starts"
        inputs.each {
            it.directoryInputs.each {
                def dirPath = it.file.absolutePath
                logger.info "dirPath $dirPath"
                it.file.eachFileRecurse(FileType.FILES) {
                    if (it.absolutePath.endsWith(SdkConstants.DOT_CLASS)) {
                        def className =
                                it.absolutePath.substring(
                                        dirPath.length() + 1,
                                        it.absolutePath.length() - SdkConstants.DOT_CLASS.length()
                                ).replace(File.separatorChar, '.' as char)
                        classNames.add(className)
                    }
                }
            }

            it.jarInputs.each {
                def jarFile = new JarFile(it.file)
                logger.info "jarFile " + jarFile.name
                jarFile.entries().each {
                    logger.info "each " + it.name
                    if (it.name.endsWith(SdkConstants.DOT_CLASS)) {
                        logger.info "class " + it.name
                        classNames.add(it.name.substring(0, it.name.length() - SdkConstants.DOT_CLASS.length())
                                .replace(File.separatorChar, '.' as char))
                    }
                }
            }
        }
        logger.info "getClassNames ends"

        return classNames
    }

    private static ClassPool createClassPool(Collection<TransformInput> inputs) {
        // Don't use ClassPool.getDefault(). Doing consecutive builds in the same run (e.g. debug+release)
        // will use a cached object and all the classes will be frozen.
        ClassPool classPool = new ClassPool(null)
        classPool.appendSystemPath()
        classPool.appendClassPath(new LoaderClassPath(getClass().getClassLoader()))

        inputs.each {
            it.directoryInputs.each {
                classPool.appendClassPath(it.file.absolutePath)
            }

            it.jarInputs.each {
                classPool.appendClassPath(it.file.absolutePath)
            }
        }

        return classPool
    }

    private static Set<CtMethod> getPackageMethods(ClassPool classPool, Set<String> classNames, String packageName) {
        Logger logger = LoggerFactory.getLogger('realm-logger')
        logger.info "getPackageMethods started."
        Set<CtMethod> methods = new HashSet<CtMethod>();
        def pkgNameQuote = Pattern.quote(packageName)
        def pattern = Pattern.compile("^$pkgNameQuote\\.[^\\.]*\$")
        logger.info "$pattern"
        classNames.findAll {
            if (it.matches(pattern)) {
                logger.info "getPackageMethods $it"
                return true
            }
            return false
        }.each {
            methods.addAll(classPool.get(it).getDeclaredMethods())
        }
        logger.info "getPackageMethods end."
        return methods
    }
}
