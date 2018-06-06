package randoop.reflection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import randoop.operation.NonreceiverTerm;
import randoop.operation.TypedOperation;
import randoop.sequence.Sequence;
import randoop.sequence.Variable;
import randoop.types.ClassOrInterfaceType;
import randoop.util.ClassFileConstants;
import randoop.util.MultiMap;

/**
 * {@code ClassLiteralExtractor} is a {@link ClassVisitor} that extracts literals from the bytecode
 * of each class visited, adding a sequence for each to a map associating a sequence with a type.
 *
 * @see OperationModel
 */
class ClassLiteralExtractor extends DefaultClassVisitor {

  private MultiMap<ClassOrInterfaceType, Sequence> literalMap;

  /**
   * The map of literals to their term frequency: tf(t,d), where t is a literal and d is all classes
   * under test. Note that this is the raw frequency, just the number of times they occur within all
   * classes under test.
   */
  private final Map<Sequence, Integer> literalsTermFrequency;

  ClassLiteralExtractor(
      MultiMap<ClassOrInterfaceType, Sequence> literalMap,
      Map<Sequence, Integer> literalsTermFrequency) {
    this.literalMap = literalMap;
    this.literalsTermFrequency = literalsTermFrequency;
  }

  @Override
  public void visitBefore(Class<?> c) {
    Collection<ClassFileConstants.ConstantSet> constList = new ArrayList<>();
    constList.add(ClassFileConstants.getConstants(c.getName()));
    MultiMap<Class<?>, NonreceiverTerm> constantMap = ClassFileConstants.toMap(constList);
    for (Class<?> constantClass : constantMap.keySet()) {
      for (NonreceiverTerm term : constantMap.getValues(constantClass)) {
        Sequence seq =
            new Sequence()
                .extend(
                    TypedOperation.createNonreceiverInitialization(term),
                    new ArrayList<Variable>());

        // Map from the class to the class literal.
        ClassOrInterfaceType constantType = ClassOrInterfaceType.forClass(constantClass);
        literalMap.add(constantType, seq);

        Integer currFrequency = literalsTermFrequency.get(seq);
        if (currFrequency == null) {
          currFrequency = 0;
        }
        literalsTermFrequency.put(seq, currFrequency + term.getFrequency());
      }
    }
  }
}
