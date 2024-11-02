package randoop.generation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;
import randoop.DummyVisitor;
import randoop.ExecutionOutcome;
import randoop.NormalExecution;
import randoop.main.RandoopUsageError;
import randoop.operation.CallableOperation;
import randoop.operation.ConstructorCall;
import randoop.operation.MethodCall;
import randoop.operation.TypedClassOperation;
import randoop.operation.TypedOperation;
import randoop.reflection.OperationExtractor;
import randoop.sequence.ExecutableSequence;
import randoop.sequence.Sequence;
import randoop.sequence.SequenceCollection;
import randoop.test.DummyCheckGenerator;
import randoop.types.ArrayType;
import randoop.types.NonParameterizedType;
import randoop.types.Type;
import randoop.types.TypeTuple;
import randoop.util.EquivalenceChecker;
import randoop.util.Log;
import randoop.util.Randomness;
import randoop.util.SimpleArrayList;
import randoop.util.SimpleList;

/**
 * A demand-driven approach to construct inputs. Randoop works by selecting a method and then trying
 * to find inputs for that method. Ordinarily, Randoop works bottom-up: if Randoop cannot find
 * inputs for the selected method, it gives up and selects a different method. This demand-driven
 * approach works top-down: if Randoop cannot find inputs for the selected method, then it looks for
 * methods that create values of the necessary type, and iteratively tries to call them.
 *
 * <p>The demand-driven approach implements the "Detective" component described by the ASE 2015
 * paper <a href="https://people.kth.se/~artho/papers/lei-ase2015.pdf">"GRT: Program-Analysis-Guided
 * Random Testing" by Ma et al.</a> .
 */
public class DemandDrivenInputCreator {
  /**
   * The sequence collection that contains the sequences that are used to construct the inputs for
   */
  private final SequenceCollection sequenceCollection;

  /**
   * If true, {@link #createInputForType(Type)} returns only sequences that declare values of the
   * exact type that was requested.
   */
  private boolean exactTypeMatch;

  /**
   * If true, {@link #createInputForType(Type)} returns only sequences that are appropriate to use
   * as a method call receiver, i.e., Type.isNonreceiverType() returns false for the type of the
   * variable created by the sequence.
   */
  private boolean onlyReceivers;

  // TODO: The original paper uses a "secondary object pool (SequenceCollection in Randoop)"
  // to store the results of the demand-driven input creation. This theoretically reduces
  // the search space for the missing types. Consider implementing this feature and test whether
  // it improves the performance.

  /** Constructs a new {@code DemandDrivenInputCreation} object. */
  public DemandDrivenInputCreator(
      SequenceCollection sequenceCollection, boolean exactTypeMatch, boolean onlyReceivers) {
    this.sequenceCollection = sequenceCollection;
    this.exactTypeMatch = exactTypeMatch;
    this.onlyReceivers = onlyReceivers;
  }

  /**
   * Performs a demand-driven approach for constructing input objects of a specified type, when the
   * sequence collection contains no objects of that type.
   *
   * <p>This method identifies a set of producer methods/constructors that return a type that is
   * compatible with (i.e., assignable to the variable of) the specified type. For each of these
   * methods: it generates a method sequence for the method by searching for necessary inputs from
   * the provided sequence collection, executing it, and, if successful, storing the sequence in the
   * sequence collection for future use.
   *
   * <p>Finally, it returns a list of sequences that produce objects of the specified type, if any
   * are found.
   *
   * <p>Here is the demand-driven algorithm in more detail:
   *
   * <ol>
   *   <li>Suppose type {@code A} is missing. Identify constructors and methods that create {@code
   *       A} (producer methods).
   *   <li>For each producer method (e.g. {@code A.foo(B, C)}):
   *       <ul>
   *         <li>Recursively apply steps 1-2 for B and C if:
   *             <ul>
   *               <li>The type is not primitive.
   *               <li>The type has not been processed.
   *             </ul>
   *       </ul>
   *   <li>Iterate through all producer methods, creating and executing sequences.
   *   <li>Store successful sequences in the sequence collection.
   *   <li>Return sequences that produce objects of type {@code A}.
   * </ol>
   *
   * <p>Note that a single call to this method may not be sufficient to construct the specified
   * type, even when possible sequences exist. The method may need to be called multiple times to
   * successfully construct the object. If no sequences are found in a single run but the sequence
   * can be possibly constructed, the call to this method often constructs intermediate sequences
   * and store them in the sequence collection that can help future runs of demand-driven input
   * creation to succeed.
   *
   * <p>Invariant: This method is only called when the component sequence collection ({@link
   * ComponentManager#gralComponents}) is lacking a sequence that creates an object of a type
   * compatible with the one required by the forward generator. See {@link
   * randoop.generation.ForwardGenerator#selectInputs(TypedOperation)}
   *
   * @param t the type of objects to create
   * @return method sequences that produce objects of the specified type if any are found, or an
   *     empty list otherwise
   */
  public SimpleList<Sequence> createInputForType(Type t) {
    // Constructors/methods that return the demanded type.
    Set<TypedOperation> producerMethods = getProducers(t);

    // Check if there are no producer methods
    if (producerMethods.isEmpty()) {
      // Warn the user
      Log.logPrintf(
          "Warning: No producer methods found for type %s. Cannot generate inputs for this type.%n",
          t);
      // Track the type with no producers
      UninstantiableTypeTracker.addType(t);
      return new SimpleArrayList<>();
    }

    // For each producer method, create a sequence if possible.
    // Note: The order of methods in `producerMethods` does not guarantee that all necessary
    // methods will be called in the correct order to fully construct the specified type in one call
    // to demand-driven `createInputForType`.
    // Intermediate objects are added to the sequence collection and may be used in future tests.
    for (TypedOperation producerMethod : producerMethods) {
      Sequence newSequence = createSequenceForOperation(producerMethod);
      if (newSequence != null) {
        // If the sequence is successfully executed, add it to the sequenceCollection.
        executeAndAddToPool(Collections.singleton(newSequence));
      }
    }

    // Note: At the beginning of the `createInputForType` call, getSequencesForType here would
    // return an empty list. However, it is not guaranteed that the method will return a non-empty
    // list at this point.
    // It may take multiple calls to `createInputForType` during the forward generation process
    // to fully construct the specified type to be used.
    SimpleList<Sequence> result =
        sequenceCollection.getSequencesForType(t, exactTypeMatch, onlyReceivers);

    return result;
  }

  /**
   * Returns methods that that return objects of the specified type.
   *
   * <p>Note that the order of the {@code TypedOperation} instances in the resulting set does not
   * necessarily reflect the order in which methods need to be called to construct types needed by
   * the producers.
   *
   * @param t the return type of the resulting methods
   * @return a set of {@code TypedOperations} (constructors and methods) that return objects of the
   *     specified type {@code t}. May return an empty set.
   */
  public Set<TypedOperation> getProducers(Type t) {
    Set<TypedOperation> producerMethods = new LinkedHashSet<>();

    Set<Type> specifiedTypes = new LinkedHashSet<>();
    for (String className : UnspecifiedClassTracker.getSpecifiedClasses()) {
      try {
        Class<?> cls = Class.forName(className);
        specifiedTypes.add(new NonParameterizedType(cls));
      } catch (ClassNotFoundException e) {
        throw new RandoopUsageError("Class not found: " + className);
      }
    }
    specifiedTypes.add(t);

    // Search for constructors/methods that can produce the specified type.
    producerMethods.addAll(getProducers(t, specifiedTypes));

    return producerMethods;
  }

  /**
   * Returns constructors/methods that return objects of the specified type.
   *
   * <p>Starting from {@code startingType}, examine all visible constructors/methods in it that
   * return a type compatible with the specified type {@code t}. It recursively processes the inputs
   * needed to execute these constructors and methods.
   *
   * @param t the return type of the resulting methods
   * @param startingTypes the types to start the search from
   * @return a set of {@code TypedOperations} (constructors and methods) that return the specified
   *     type {@code t}
   */
  private static Set<TypedOperation> getProducers(Type t, Set<Type> startingTypes) {
    Set<TypedOperation> result = new LinkedHashSet<>();
    Set<Type> processed = new HashSet<>();
    Queue<Type> workList = new ArrayDeque<>(startingTypes);

    while (!workList.isEmpty()) {
      Type currentType = workList.poll();

      // Skip if already processed or if it's a non-receiver type
      if (processed.contains(currentType) || currentType.isNonreceiverType()) {
        continue;
      }
      processed.add(currentType);

      Class<?> currentClass = currentType.getRuntimeClass();
      List<Executable> executableList = new ArrayList<>();

      // Add constructors if the current type is assignable to t and not abstract
      if (t.isAssignableFrom(currentType) && !Modifier.isAbstract(currentClass.getModifiers())) {
        Collections.addAll(executableList, currentClass.getConstructors());
      }

      // Add methods that return the target type t
      Collections.addAll(executableList, currentClass.getMethods());

      for (Executable executable : executableList) {
        Type returnType;
        if (executable instanceof Constructor) {
          returnType = new NonParameterizedType(currentClass);
        } else if (executable instanceof Method) {
          Method method = (Method) executable;
          returnType = Type.forClass(method.getReturnType());
          if (!t.isAssignableFrom(returnType)) {
            continue; // Skip methods that don't return a compatible type
          }
        } else {
          continue; // Skip other types of executables
        }

        // Obtain the input types and output type of the executable.
        List<Type> inputTypeList =
            OperationExtractor.classArrayToTypeList(executable.getParameterTypes());
        // If the executable is a non-static method, add the receiver type to the front of the input
        // type list.
        if (executable instanceof Method && !Modifier.isStatic(executable.getModifiers())) {
          inputTypeList.add(0, new NonParameterizedType(currentClass));
        }
        TypeTuple inputTypes = new TypeTuple(inputTypeList);
        CallableOperation callableOperation =
            (executable instanceof Constructor)
                ? new ConstructorCall((Constructor<?>) executable)
                : new MethodCall((Method) executable);
        NonParameterizedType declaringType = new NonParameterizedType(currentClass);
        TypedOperation typedClassOperation =
            new TypedClassOperation(callableOperation, declaringType, inputTypes, returnType);

        // Add the method call to the result.
        result.add(typedClassOperation);

        // Add parameter types to the workList for further processing
        for (Type paramType : inputTypeList) {
          if (!paramType.isPrimitive() && !processed.contains(paramType)) {
            workList.add(paramType);
          }
        }
      }
    }

    return result;
  }

  /**
   * This method creates a new sequence for the given {@code TypedOperation}. The method iteratively
   * searches for the necessary inputs from the sequence collection. If the inputs are found, the
   * method creates a new sequence and returns it. If the inputs are not found, the method returns
   * {@code null}.
   *
   * @param typedOperation the operation for which input sequences are to be generated
   * @return a sequence for the given {@code TypedOperation}, or {@code null} if the inputs are not
   *     found
   */
  private @Nullable Sequence createSequenceForOperation(TypedOperation typedOperation) {
    TypeTuple inputTypes = typedOperation.getInputTypes();
    List<Sequence> inputSequences = new ArrayList<>();

    // Represents the position of a statement within a sequence.
    // Used to keep track of the index of the statement that generates an object of the required
    // type.
    int index = 0;

    // Create an input type to index mapping.
    // This allows us to find the exact statements in a sequence that generate objects
    // of the type required by the typedOperation.
    Map<Type, List<Integer>> typeToIndex = new HashMap<>();

    for (int i = 0; i < inputTypes.size(); i++) {
      // Get a set of sequences, each of which generates an object of the input type of the
      // typedOperation.
      Type inputType = inputTypes.get(i);
      // Return exact type match if the input type is a primitive type, same as how it is done in
      // `ComponentManager.getSequencesForType`. However, allow non-receiver types to be considered
      // at all times.
      SimpleList<Sequence> sequencesOfType =
          sequenceCollection.getSequencesForType(inputTypes.get(i), inputType.isPrimitive(), false);

      if (sequencesOfType.isEmpty()) {
        return null;
      }

      // Randomly select a sequence from the sequencesOfType.
      Sequence seq = Randomness.randomMember(sequencesOfType);

      inputSequences.add(seq);

      // For each statement in the sequence, add the index of the statement to the typeToIndex map.
      for (int j = 0; j < seq.size(); j++) {
        Type type = seq.getVariable(j).getType();
        typeToIndex.computeIfAbsent(type, k -> new ArrayList<>()).add(index++);
      }
    }

    // The indices of the statements in the sequence that will be used as inputs to the
    // typedOperation.
    List<Integer> inputIndices = new ArrayList<>();

    // For each input type of the operation, find the indices of the statements in the sequence
    // that generates an object of the required type.
    Map<Type, Integer> typeIndexCount = new HashMap<>();
    for (Type inputType : inputTypes) {
      List<Integer> indices = findCompatibleIndices(typeToIndex, inputType);
      if (indices.isEmpty()) {
        return null; // No compatible type found, cannot proceed
      }

      int count = typeIndexCount.getOrDefault(inputType, 0);
      if (count < indices.size()) {
        inputIndices.add(indices.get(count));
        typeIndexCount.put(inputType, count + 1);
      } else {
        return null; // Not enough sequences to satisfy the input needs
      }
    }

    return Sequence.createSequence(typedOperation, inputSequences, inputIndices);
  }

  /**
   * Given a map of types to indices and a target type, this method returns a list of indices that
   * are compatible with the target type. This method considers boxing equivalence when comparing
   * boxed and unboxed types, but does not consider subtyping.
   *
   * @param typeToIndex a map of types to indices
   * @param t the target type
   * @return a list of indices that are compatible with the target type
   */
  private List<Integer> findCompatibleIndices(Map<Type, List<Integer>> typeToIndex, Type t) {
    List<Integer> compatibleIndices = new ArrayList<>();
    for (Map.Entry<Type, List<Integer>> entry : typeToIndex.entrySet()) {
      if (EquivalenceChecker.areEquivalentTypesConsideringBoxing(entry.getKey(), t)) {
        compatibleIndices.addAll(entry.getValue());
      }
    }
    return compatibleIndices;
  }

  /**
   * Executes a set of sequences and add the successfully executed sequences to the sequence
   * collection allowing them to be used in future tests. A successful execution is a normal
   * execution and yields a non-null value.
   *
   * @param sequenceSet a set of sequences to be executed
   */
  private void executeAndAddToPool(Set<Sequence> sequenceSet) {
    for (Sequence genSeq : sequenceSet) {
      ExecutableSequence eseq = new ExecutableSequence(genSeq);
      eseq.execute(new DummyVisitor(), new DummyCheckGenerator());

      Object generatedObjectValue = null;
      ExecutionOutcome outcome = eseq.getResult(eseq.sequence.size() - 1);
      if (outcome instanceof NormalExecution) {
        generatedObjectValue = ((NormalExecution) outcome).getRuntimeValue();
      }

      if (generatedObjectValue != null) {
        sequenceCollection.add(genSeq);
      }
    }
  }

  /**
   * Checks if the type was specified by the user. If not, logs the class as an unspecified class.
   *
   * @param type the type of the object to check for specification
   */
  private static void logIfUnspecified(Type type) {
    String className;
    if (type.isArray()) {
      className = ((ArrayType) type).getElementType().getRuntimeClass().getName();
    } else {
      className = type.getRuntimeClass().getName();
    }

    // Add the class to the unspecified classes if it is not specified by the user.
    if (!UnspecifiedClassTracker.getSpecifiedClasses().contains(className)) {
      UnspecifiedClassTracker.addClass(type.getRuntimeClass());
    }
  }
}
