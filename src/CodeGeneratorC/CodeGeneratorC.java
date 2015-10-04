package CodeGeneratorC;

import AST.*;
import Utilities.*;
import Utilities.Error;
import org.stringtemplate.v4.*;
import java.io.*;
import java.util.*;
import NameCollector.*;

/**
 * Code Geneartor turns processJ code to equivalent C code using using
 * parse tree by visiting each node. It then recusively builds the program
 * through recursion returning strings rendered by the string template.
 * Notice sometimes we return String[] if there is multiple statements.
 * General rules of thumb:
 * 1) Anything that is wrapped in a sequence will be passed up as an String[].
 *    it will then be passed to ST as an String[] where the equivalent ST rule
 *    will have a <X;separator = " S"> where S is a string. Therefore the sequence
 *    be delimited by say newlines or commas depending on the proper context.
 * 2) Optional parameters are implemented using <if(X)> <endif> blocks in the ST.
 */
public class CodeGeneratorC <T extends Object> extends Visitor<T> {
  //====================================================================================
  /** TODO figure out if this is even needed?? */
  public static SymbolTable symtab = new SymbolTable();

  /** Relative location of our group string template file.*/
  private final String grammarStFile = "src/StringTemplates/grammarTemplates.stg";
  /** Name of the parameter of type Workspace that all function needs*/
  private final String globalWorkspace = "WP";
  /** To figure out in more detail and perhaps chose best value at runtime?*/
  private final int stackSize = 2000;
  /** Object containing a group of  string templates. */
  private STGroup group;
  /**
   * We must know the name of the last function of the program as this is
   * the function that will be called by the CCSP runtime to start running
   * the program, this is the last function on the file. */
  private String lastFunction = null;
  private Boolean inLastFunction = false;
  /**
   * We need to create a: barrier, word, and Worspace variable for each invocation.
   * We do this by appending the S := "barrier" | "word" | "ws" to the function name
   * S ++ functionName, this works except when the same function is called multiple
   * times in the same scope. Therefore this hashtable keeps track of the proper number
   * to append to the end of the functionName to avoid this as an issue. */
  private Hashtable<String, Integer> functionCounts;
  /**
   * This Hash Table is used to create temporary functions with unique names. Everytime
   * we hit a ProcDecl we know we have a new function and are looking at a new scope,
   * hence we might have a ParBlock{...} so we create a new entry in this function and
   * and set the amount to 0 from here for every statement that needs to be wrapped in
   * a function we give it a name of the form <NameOfFunction>ParBlockStmt<counterValue>.
   */
  private Hashtable<String, Integer> parBlockStmtCounts;
  /**
   * We must know when we are inside a ParBlock as this means we have to do eveything
   * with pointers to NameExpr instead of actual variales.
   */
  private Boolean inParBlock = false;
  // /**
  //  * Similar to above, except if we are in the createParameters function we don't
  //  * want this behavior when we recurse to visitNameExpr().
  //  */
  // private Boolean inCreateParameters = false;
  /** I belive a second in their time units is defined as this value (one million): */
  private final int second = 1000000;
  /**
   * Keeps track of the current function we are inside of. This is done for ParBlock
   * uses this to generate the name of our <...>ParBlockStmt<...> function name. see
   * @parBlockstmtCounts for more info.
   */
  private String currentFunction = null;
  /**
   * These two variables are responsible for holding the auto generated functions
   * created by ParBlocks, one holds the prototypes for the top of the program while the
   * other holds the actual declaration. Then at VisitCompilation they are added to our
    * template.
   */
  private LinkedList<String> parBlockPrototypes = null;
  private LinkedList<String> parBlockProcs = null;
  //====================================================================================
  public CodeGeneratorC(){
    Log.log("======================================");
    Log.log("* C O D E   G E N E R A T O R  ( C )  *");
    Log.log("======================================");

    //Load our string templates from specified directory.
    this.group = new STGroupFile(grammarStFile);
    //Create hashtables!
    this.functionCounts = new Hashtable();
    this.parBlockStmtCounts = new Hashtable();

    this.parBlockPrototypes = new LinkedList();
    this.parBlockProcs = new LinkedList();

    return;
  }
  //====================================================================================
  // AltCase
  /**
   * The AltCase visitor produces the string that will go inside the case of the switch
   * statement. The equivalent C code must look something like:
   * case N:
   *   <Expression Statement which was our guard.>;
   *   <Rest of statements inside the stat block>;
   *   break;
   */
  public T visitAltCase(AltCase ac) {
    Log.log(ac.line + ": Visiting an AltCase");
    ST template = group.getInstanceOf("AltCase");

    //TODO preconditions.
    Statement caseExprStmt = ac.guard().guard();
    Statement stat = ac.stat();
    int caseNumber = ac.getCaseNumber();
    String caseExprStr;

    //We treat TimeoutStat differently:
    if(caseExprStmt instanceof TimeoutStat)
      caseExprStr = "/*Timeout do nothing here!*/";
    else
      //Two more posibilities for myExpr: SkipStat | ExprStat (Channel)
      caseExprStr = (String)caseExprStmt.visit(this);

    //Make the actual block of statements to run, we know this must always be a block so,
    //no need to check what is returned.
    String[] statementList = (String[])stat.visit(this);

    template.add("number", caseNumber);
    template.add("guardToDo", caseExprStr);
    template.add("statementList", statementList);

    return (T) template.render();
  }
  //====================================================================================
  // AltStat
  /**
   * Alt cases are also nontrivial. Granted, they are not hard. The main layout of any
   * alt case in CCSP looks like:
   * <timerAlt(..) | Alt(..)> //Initialization for Alternate statement.
   * <AltEnableChannel(..) | AltEnableTimer(..) | AltEnableSkip(..)> //Initialization
   *   of all cases that will partake in the alt statement.
   * <timerAltWait(...) | AltWait(...)> //Actual place where we will wait.
   * <AltDisableChannel(..) | AltDisableTimer(..) | AltDisableSkip(..) //UnInitialization
   * <switch( AltEnd(<workspaceName> ){
   *    <cases for switch>:
   *      <expression from guard>
   *      <statements from this condition>
   *    ...
   * }
   *
   * This is all! So we use our String Templates to build this shape.
   */
     //TODO: PRIALT
  public T visitAltStat(AltStat as) {
    Log.log(as.line + ": Visiting an AltStat");
    ST template = group.getInstanceOf("AltStat");
    Sequence<AltCase> altCaseList = as.body();
    int count = altCaseList.size();
    boolean hasTimeout = false;

    //If the alt uses timeout we must use a different function to invoke the alt.
    for(int i = 0; i < count; i++){
      AltCase altCase = altCaseList.getElementN(i);
      hasTimeout = caseIsTimeout(altCase);

      if(hasTimeout == true)
        break;
    }

    //Create the Alt() and AltWait().
    String altTypeStr = "NormalAltType";
    String waitTypeStr = "NormalWaitType";
    if(hasTimeout == true){
      altTypeStr = "TimerAltType";
      waitTypeStr = "TimerWaitType";
    }

    ST altTypeTemplate = group.getInstanceOf(altTypeStr);
    ST waitTypeTemplate = group.getInstanceOf(waitTypeStr);

    //These lists hold our strings representing the {"AltEnablechannel(<name>)",...}
    //for our alt statement.
    String[] enableList = createEnableDisable(altCaseList, true);
    String[] disableList = createEnableDisable(altCaseList, false);

    //Only thing left to do is to make the switch statement holding our cases to run.
    ST altEndTemplate = group.getInstanceOf("AltEnd");
    ST altSwitchTemplate = group.getInstanceOf("AltSwitch");

    //Create AltEnd part.
    altEndTemplate.add("globalWsName", globalWorkspace);
    String altEndStr = altEndTemplate.render();

    //Iterate over our children setting their number.
    for(int i = 0; i < count; i++){
     AltCase altCase = altCaseList.getElementN(i);
     altCase.setCaseNumber(i);
    }

    //Create all the case statements.
    String[] caseList = (String[])altCaseList.visit(this);

    //Now make our switch string.
    altSwitchTemplate.add("altEnd", altEndStr);
    altSwitchTemplate.add("caseList", caseList);
    String altSwitchStr = altSwitchTemplate.render();

    //Make our wait and alt templates.
    altTypeTemplate.add("globalWsName", globalWorkspace);
    waitTypeTemplate.add("globalWsName", globalWorkspace);

    //Make final string!
    template.add("altType", altTypeTemplate.render());
    template.add("enableChannelList", enableList);
    template.add("waitType", waitTypeTemplate.render());
    template.add("disableChannelList", disableList);
    template.add("switchStatement", altSwitchStr);

    return (T) template.render();
  }
  //====================================================================================
  // ArrayAccessExpr
  //====================================================================================
  // ArrayLiteral
  //====================================================================================
  // ArrayType
  //====================================================================================
  // Assignment
  public T visitAssignment(Assignment as){
    Log.log(as.line + ": Visiting an Assignment");
    ST template = group.getInstanceOf("Assignment");

    String left = (String) as.left().visit(this);
    String right = (String) as.right().visit(this);
    String op = (String) as.opString();

    template.add("left",left);
    template.add("right",right);
    template.add("op",op);

    return (T) template.render();
  }
  //====================================================================================
  // BinaryExpr
  public T visitBinaryExpr(BinaryExpr be){
    Log.log(be.line + ": Visiting a Binary Expression");
    ST template = group.getInstanceOf("BinaryExpr");

    String left = (String) be.left().visit(this);
    String right = (String) be.right().visit(this);
    String op = (String) be.opString();

    //TODO: Add suport for string concatanation here.

    template.add("left",left);
    template.add("right",right);
    template.add("op",op);

    return (T) template.render();
  }
  //====================================================================================
  // Block
  public T visitBlock(Block bl){
    Log.log(bl.line + ": Visiting a Block");
    String[] statements = (String[]) bl.stats().visit(this);

    return (T) statements;
  }
  //====================================================================================
  // BreakStat //TODO: Add identifier option.
  public T visitBreakStat(BreakStat bs){
    Log.log(bs.line + ": Visiting a BreakStat");
    ST template = group.getInstanceOf("BreakStat");
    //Can be null.
    Name name = bs.target();

    if(name != null){
      String nameStr = (String) name.visit(this);
      //Add name option here.
    }

    return (T) template.render();
  }
  //====================================================================================
  // CastExpr
  public T visitCastExpr(CastExpr ce){
    Log.log(ce.line + ": Visiting a Cast Expression");
    ST template = group.getInstanceOf("CastExpr");
    //No node for type get actual string.
    String ct = ce.type().typeName();
    String expr = (String) ce.expr().visit(this);

    template.add("ct", ct);
    template.add("expr", expr);


    return (T) template.render();
  }
  //====================================================================================
  // ChannelType
  /**
   * TODO: Change channel implementation from Channel* as the default data type to
   * just Channel.
   * Channels are difficult as they have a type Channel, yet they are passed as pointers
   * when actually doing stuff. So to keep our logic clean and not worry whether this
   * is a Channel, or a Channel* inside a function we consider the only type to be
   * Channel*.
   */
  public T visitChannelType(ChannelType ct){
    Log.log(ct.line + ": Visiting a Channel Type!");
    return (T) "Channel*";
  }
  //====================================================================================
  // ChannelEndExpr
  public T visitChannelEndExpr(ChannelEndExpr ce){
    Log.log(ce.line + ": Visiting a Channel End Expression!");
    //TODO: Figure out what else could be in a ChannelEndExpr.
    String channel = (String) ce.channel().visit(this);

    return (T) channel;
  }
  //====================================================================================
  // ChannelEndType
  public T visitChannelEndType(ChannelEndType ct){
    Log.log(ct.line + ": Visiting a Channel End Type!");
    //C has no concept of ChannelEnd Types it is just the pointer to that channel.
    return (T) "Channel*";
  }
  //====================================================================================
  // ChannelReadExpr
  public T visitChannelReadExpr(ChannelReadExpr cr){
    Log.log(cr.line + ": Visiting ChannelReadExpr");
    //This needs to be a function call in C that takes one argument:
    //    ChanInInt(wordPointer,intIn, &x);
    //We will have to use a temp varible to achieve this with notation like:
    //    int x = t.read();
    //TODO: Extend to all possible types.
    ST template = null;
    Expression channelExpr = cr.channel();
    NameExpr channelNameExpr = null;
    //This is either a NameExpression (chan.read()) or a ChannelEndExpr (chan.read.read())
    if(channelExpr instanceof NameExpr)
      channelNameExpr = (NameExpr) channelExpr;
    else if(channelExpr instanceof ChannelEndExpr)
      channelNameExpr = (NameExpr)((ChannelEndExpr)channelExpr).channel();

    String channel = (String) channelNameExpr.visit(this);
    Type myType = null;


    //TODO: Clean this mess up.
    if(channelNameExpr.myDecl instanceof LocalDecl)
      //Figure out type of channel and do appropriate code generation based on this.
      myType = ((LocalDecl)channelNameExpr.myDecl).type();
    if(channelNameExpr.myDecl instanceof ParamDecl)
      //Figure out type of channel and do appropriate code generation based on this.
      myType = ((ParamDecl)channelNameExpr.myDecl).type();

    //Add multiple types for different things here: TODO add all types.
    //Posibility One: Timers!
    if( myType.isTimerType() ){
      template = group.getInstanceOf("ChannelReadExprTimer");
      template.add("globalWsName", globalWorkspace);
    }
    //Posibility Two: This is an actual end: chan<type>.read chan,
    //chan.read()
    else if( myType.isChannelEndType() ){
      Type baseType = ((ChannelEndType)myType).baseType();

      if( baseType.isIntegerType() || baseType.isBooleanType()){
        template = group.getInstanceOf("ChannelReadExprInt");
        template.add("globalWsName", globalWorkspace);
        template.add("channel", channel);
      }
      else{
        String errorMsg = "Unsupported type: %s for ChannelEndType!";
        String error = String.format(errorMsg, baseType.toString());
        Error.error(cr, error);
      }
    }
    //Posibility Three: This is a channel to be treated as an end to avoid
    //chan.read.read().
    else if( myType.isChannelType() ){
      Type baseType = ((ChannelType)myType).baseType();

      if( baseType.isIntegerType() || baseType.isBooleanType() ){
        template = group.getInstanceOf("ChannelReadExprInt");
        template.add("globalWsName", globalWorkspace);
        template.add("channel", channel);
      }
      else{
        String errorMsg = "Unsupported type: %s for ChannelEndType!";
        String error = String.format(errorMsg, baseType.toString());
        Error.error(cr, error);
      }
    }
    else{
      String errorMsg = "Unsupported type: %s for ChannelReadExpr.";
      String error = String.format(errorMsg, myType.typeName());
      Error.error(cr, error);
    }

    return (T) template.render();
  }
  //====================================================================================
  // ChannelWriteStat
  public T visitChannelWriteStat(ChannelWriteStat cw){
    Log.log(cw.line + ": Visiting a Channel Write Statement!");

    ST template = group.getInstanceOf("ChannelWriteStat");
    String expr = (String) cw.expr().visit(this);
    String channel = (String) cw.channel().visit(this);
    //TODO: template should be based on type of expression!
    //Only int works for now.
    template.add("globalWsName", globalWorkspace);
    template.add("channel", channel);
    template.add("expr", expr);

    return (T) template.render();
  }
  //====================================================================================
  // ClaimStat
  //====================================================================================
  // Compilation
  public T visitCompilation(Compilation c){
    Log.log(c.line + ": Visiting the Compilation");

    ST template = group.getInstanceOf("Compilation");
    LinkedList<String> prototypes = new LinkedList();

    //We add our function prototypes here as the C program will need them.
    Sequence<Type> typeDecls = c.typeDecls();

    //Iterate over the sequence only collecting the procType arguments. This is needed
    //to know the name of the last functions as well as populate our parBlockStmtCounts.
    for (int i = 0; i < typeDecls.size(); i++) //TODO: Null pointer exception if program is empty?
      if (typeDecls.child(i) instanceof ProcTypeDecl){
        ProcTypeDecl current = (ProcTypeDecl)typeDecls.child(i);
        String prototypeName = getPrototypeString(current);

        prototypes.add(prototypeName);
        String name = current.name().getname();

        this.lastFunction = name;
      }

    //TODO add the pragmas, packageName and imports later!
    //Recurse to all children getting strings needed for this Class' Template.
    String[] typeDeclsStr = (String[]) c.typeDecls().visit(this);

    //This is where functions are created as they are procedure type.
    template.add("prototypes", prototypes);
    template.add("typeDecls", typeDeclsStr);
    //ProcessJ is set so the last function in the file is called as the main,
    //we do that here by specifiying which function to call.
    template.add("functionToCall", lastFunction);
    template.add("parBlockPrototypes", parBlockPrototypes);
    template.add("parBlockProcs", parBlockProcs);

    //Finally write the output to a file
    String finalOutput = template.render();
    writeToFile(finalOutput);
    Log.log("Output written to file codeGenerated.c");

    return (T) finalOutput;
  }
  //====================================================================================
  // ConstantDecl
  //====================================================================================
  // ContinueStat //TODO: add identifier option.
  public T visitContinueStat(ContinueStat cs){
    Log.log(cs.line + ": Visiting a ContinueStat");

    ST template = group.getInstanceOf("ContinueStat");
    //Can be null.
    Name name = cs.target();

    if(name != null){
      String nameStr = (String) name.visit(this);
      //Add name option here.
    }

    return (T) template.render();
  }
  //====================================================================================
  // DoStat
  //TODO: I think this will crash if we do:
  //  do
  //    <oneStat>
  //  while(<expr>;
  //Since this does not return a Strig[]
  public T visitDoStat(DoStat ds){
    Log.log(ds.line + ": Visiting a DoStat");

    ST template = group.getInstanceOf("DoStat");
    String[] stats = (String[]) ds.stat().visit(this);
    String expr = (String) ds.expr().visit(this);

    template.add("stat", stats);
    template.add("expr", expr);

    return (T) template.render();
  }
  //====================================================================================
  // ExprStat
  public T visitExprStat(ExprStat es){
    Log.log(es.line + ": Visiting a ExprStat");

    return (T) es.expr().visit(this);
  }
  //====================================================================================
  // ExternType
  //====================================================================================
  // ForStat
  public T visitForStat(ForStat fs){
    Log.log(fs.line + ": Visiting a ForStat");
    ST template = group.getInstanceOf("ForStat");

    String expr = (String) fs.expr().visit(this);
    Sequence<Statement> init = fs.init();
    Sequence<ExprStat> incr = fs.incr();
    String[] initStr = null;
    String[] incrStr = null;
    //TODO: Barriers

    //Depending whether there is curly brackets it may return an array, or maybe just
    //a single object. So we must check what it actually is!
    Object stats = fs.stats().visit(this);

    if(stats instanceof String[])
      template.add("stats", (String[]) stats);
    else
      template.add("stats", (String) stats);

    //Check for null >:(
    if(init != null)
      initStr = (String[]) init.visit(this);
    if(incr != null)
      incrStr = (String[]) incr.visit(this);

    template.add("init", initStr);
    template.add("incr", incrStr);
    template.add("expr", expr);

    return (T) template.render();
  }
  //====================================================================================
  //TimeoutStat
  public T visitTimeoutStat(TimeoutStat ts){
    Log.log(ts.line + ": Visiting TimeoutStat");
    ST template = group.getInstanceOf("TimeoutStat");
    //Not needed? TODO
    String timer = (String) ts.timer().visit(this);
    String delay = (String) ts.delay().visit(this);
    //TODO: add appropriate units as of know I believe it's in microseconds right now.
    //Also we need to figure out the proper semantics for this.

    template.add("globalWsName", globalWorkspace);
    template.add("delay", delay);


    return (T) template.render();
  }
  //====================================================================================
  // Guard
  public T visitGuard(Guard gd){
    Log.log(gd.line + ": Visiting Guard");

    Statement stmt = gd.guard();

    //Three posibilities for myExpr: SkipStat | TimeoutStat | ExprStat (Channel)
    if( stmt instanceof ExprStat){
      //Get Channel name. TODO make less WFT?
      Expression rightHandSide = ( (Assignment)(( (ExprStat)stmt ) ).expr() ).right();
      String name = ( (NameExpr)( ( (ChannelReadExpr)rightHandSide ).channel() ) )
        .toString();
      return (T) name;
    }

    if(stmt instanceof TimeoutStat){
      TimeoutStat timeoutStmt = (TimeoutStat)stmt;
      String name = ( (NameExpr)timeoutStmt.timer() ).toString();
      return (T) name;
    }
    //Else we don't care about the name so just return null...
    return null;
  }
  //====================================================================================
  // IfStat
  //TODO: We may want to change where we return either a String or a String[] to always
  //returnting String[] even if it only has one element...
  public T visitIfStat(IfStat is){
    Log.log(is.line + ": Visiting a ifStat");
    ST template = group.getInstanceOf("IfStat");

    String expr = (String) is.expr().visit(this);
    Statement elsePart = is.elsepart();
    Object elsePartStr = null;
    Object thenPart = is.thenpart().visit(this);

    template.add("expr", expr);

    //May be one statement or multiple statements.
    if(thenPart instanceof String[])
      template.add("thenPart", (String[]) thenPart);
    else
      template.add("thenPart", (String) thenPart);

    //May or may not be here!
    if(elsePart != null){
      elsePartStr = elsePart.visit(this);

      if(thenPart instanceof String[])
        template.add("elsePart", (String[]) elsePartStr);
      else
        template.add("elsePart", (String) elsePartStr);
    }

    return (T) template.render();
  }
  //====================================================================================
  // Import
  //====================================================================================
  /**
   * Method invocations have to be treated special by the compiler, when generating the
   * C equivalent it is not enough to simply call the function. The way that CCSP
   * requires the function call is as follows:

   * //Running a proc requires a barrier:
   * LightProcBarrier barrier;

   * //Initialize barrier by giving it the number of processes that will run with it,
   * //in this case just 1 as well as the barrier's address.
   * LightProcBarrierInit (wordPointer, &barrier, 1);

   * //Process for source function.
   * word workspace1 = WORKSPACE_SIZE(0, STACKSIZE); //Zero is the number of arguments.
   * Workspace ws1 = MAlloc (wordPointer, sizeof (word) * workspace1);
   * //Initialize process by giving it the number of parameters it will take in as well
   * //as it's workspace.
   * ws1 = LightProcInit(wordPointer, ws1, 0, STACKSIZE);
   * //Argument calls are made here if any in the form of:
   *  ProcParam(wordPointer, ws1, 0, &a); //Where 0 refers to the ith parameter you want
   * set and &a refers to the pointer to the parameter that should be passed in.

   * //Run process!
   * LightProcStart(wordPointer, &barrier, ws1, source);


   * Notice that there is no error checking done by the C code but that's okay as we
   * have done the type checking through the ProcessJ compiler.

   * Returns are done as an extra parameter to the function. We create a compound block and
   * in this compound block we declare a new varaible called returnValue, we are guaranteed
   * this won't class with other variables as it is inside a new scope. We then pass it by
   * reference where the value will be changed inside the function and the function will
   * return nothing!

   * TODO: In the future we want to only create this type of function through the CCSP if
   * there is concurrent structure that needs the CCSP API to work e.g. channels, pars
   * etc. Else we want to just call it through C as it is guranteed no to have any
   * synchonization points and would have ran all the way anyways. This is done for
   * efficiency and should be considered a optimization i.e. not a priority.
   */
  public T visitInvocation(Invocation in){ //TODO free allocated memory.
    //TODO: things go horribly wrong when doing something like f(g())...
    //This could probably be fixed by using a temporary variable.
    Log.log(in.line + ": Visiting Invocation (" + in.procedureName().getname() + ")");

    String functionName = in.procedureName().getname();

    //Add this invocation to our hashtable and get the proper number to append
    //as our counter, see functionCounts for explanataion.
    int fCount = incrementEntry(functionCounts, functionName);

    //Print statements are treated differently. TODO: In the future this will change.
    if(functionName.equals("println"))
      return (T) createPrintFunction(in);

    ST template = group.getInstanceOf("Invocation");
    //Create proper names for variables to avoid name variable already declared.
    String postFix = functionName + fCount;
    String barrierName = "barrier" + postFix;
    String wordName = "word" + postFix;
    String workspaceName = "ws" + postFix;
    Sequence<Expression> params = in.params();
    int paramNumber = params.size();

    //Get out the type belonging to this function so we know if there is a return value!
    //TODO This causes NPE.
    //String returnType = in.targetProc.returnType().typeName();
    //Boolean hasReturn = returnType.equals("void");
    //TODO: Finish implemeting type returning. Really tough right now since the type checker
    //doesn't select the proper function.
    Boolean hasReturn = false;

    //Array list for ProcParams for this invocation.
    ArrayList<String> procParams = createParameters(params, workspaceName, hasReturn,
                                                    false);

    //Handle case with return.
    if(hasReturn == true){
      //TODO once Typechecker works change to comemented out line.
      //template.add("returnType", returnType);
      template.add("returnType", "int");
      paramNumber++;
    }
    //Add all our fields to our template!
    template.add("barrierName", barrierName);
    template.add("wordName", wordName);
    template.add("workspaceName", workspaceName);
    template.add("paramNumber", paramNumber);
    template.add("functionName", functionName);
    template.add("stackSize", stackSize);
    template.add("paramWorkspaceName", globalWorkspace);
    template.add("procParams", procParams);

    return (T) template.render();
  }
  //====================================================================================
  // LocalDecl
  public T visitLocalDecl(LocalDecl ld){
    Log.log(ld.line + ": Visting LocalDecl (" + ld.type().typeName() + " " + ld.var().name().getname() + ")");
    //TODO: isConstant ??
    ST template = group.getInstanceOf("LocalDecl");
    String var = (String) ld.var().visit(this);
    String typeString= (String) ld.type().visit(this);

    //Channels require an initialization.
    if(ld.type().isChannelType() == true)
          template.add("channelPart", createChannelInit(var));

    template.add("type", typeString);
    template.add("var", var);
    Log.log(template.render());
    return (T) template.render();
  }
  //====================================================================================
  // Modifier
  //====================================================================================
  // Name
  public T visitName(Name na){
    Log.log(na.line + ": Visiting a Name");

    return (T) na.getname(); //TODO: Fix lower case 'n';
  }
  //====================================================================================
  // NamedType
  //====================================================================================
  // NameExpr
  public T visitNameExpr(NameExpr ne){
    Log.log(ne.line + ": Visiting NameExpr (" + ne.name().getname() + ")");

    //If we are inside a ParBlock we want to add a *<varName> since we have sent the
    //pointer of this variable. This is done to make sure side effects in statements
    //are applied inside ParBlocks. See @visitParBlock for more information.
    if(this.inParBlock == true)
      return (T) ("(*" + ne.toString() + ")");

    return (T) ne.toString();
  }
  //====================================================================================
  // NewArray
  //====================================================================================
  // NewMobile
  //====================================================================================
  // ParamDecl
  public T visitParamDecl(ParamDecl pd){
    //Not used, instead we use createProcGetParams.
    return null;
  }
  //====================================================================================
  // ParBlock
  /**
   * This par block wraps every statement inside of it in a function with the right
   * parameters. This is needed as the Par API function can only take in functions.
   * From here we generate the code inside of the function, which must include a *x
   * for any type of side effect that happens inside the statement.
   */
  public T visitParBlock(ParBlock pb){ //TODO: Expressions, f(g());
    Log.log(pb.line + ": Visiting a ParBlock");

    Sequence<Statement> stats = pb.stats();
    //TODO fix this so to make sure it works for nested ParBlocks:
    Boolean prevInParBlock = inParBlock;
    this.inParBlock = true;

    //List containing the variadic parameter part needed for ProcPar().
    ArrayList<String> procParList = new ArrayList();
    //List that will hold all the invocation strings per function.
    ArrayList<String> statList = new ArrayList();
    //Template continually passed to createInvocationPar() so it can be updated as
    //new arguments are added, mainly needed as there is no other simple way of knowing
    //what that function and that workspace where called as in f1, f2, f3, etc.
    ST procParTemplate = group.getInstanceOf("ProcPar");
    //Template holding the actual block like syntax.
    ST parBlockTemplate = group.getInstanceOf("ParBlock");

    for(Statement myStat : stats){
      //This set contains all the NameExpr for this statement.
      LinkedList<NameExpr> myNames = new LinkedList();
      //Visit our NameCollector for our current statement to get all the NameExpr's!
      myStat.visit(new NameCollector(myNames));

      //Create function to wrap this ParBlock Statement.
      int functionNumber = incrementEntry(this.parBlockStmtCounts, currentFunction);
      String functionName = currentFunction + "ParBlockStmt" + functionNumber;
      //Create invocations statements.
      statList.add( createInvocationPar(functionName, myNames, procParList) );
      //Now create actual function body!
      parBlockProcs.add( createParBlockProc(functionName, myStat, myNames) );

      //Add this to our ParBlockPrototypes.
      parBlockPrototypes.add( getSimplePrototypeString(functionName) );
    }

    //By now procParList is populated so we may add it to our template.
    procParTemplate.add("paramWorkspaceName", globalWorkspace);
    procParTemplate.add("processNumber", stats.size());
    procParTemplate.add("list", procParList);

    parBlockTemplate.add("stringStats", statList);
    parBlockTemplate.add("procPar", procParTemplate.render());

    //Restore previous state, this matters in nested ParBlocks.
    this.inParBlock = prevInParBlock;

    return (T) parBlockTemplate.render();
  }
  //====================================================================================
  // Pragma
  //====================================================================================
  // PrimitiveLiteral
  public T visitPrimitiveLiteral(PrimitiveLiteral li){
    Log.log(li.line + ": Visiting a Primitive Literal");

    return (T) li.getText();
  }
  //====================================================================================
  // PrimitiveType
  public T visitPrimitiveType(PrimitiveType py){
    Log.log(py.line + ": Visiting a Primitive Type");

    String typeString = py.toString();
    //Here we list all the primitive types that don't perfectly translate to C.
    if(py.isStringType() == true)
      typeString = "char*";
    if(py.isTimerType() == true)
      typeString = "Time";
    if(py.isBooleanType() == true)
      typeString = "bool";
    //TODO: add Boolean, barrier, timer.
    return (T) typeString;
  }
  //====================================================================================
  // ProcTypeDecl
  public T visitProcTypeDecl(ProcTypeDecl pd){
    Log.log(pd.line + ": Visiting a Proc Type Decl");

    ST template = group.getInstanceOf("ProcTypeDecl");

    Sequence<Modifier> modifiers = pd.modifiers();
    String name = (String) pd.name().visit(this);
    //Set our current function.
    this.currentFunction = name;
    String returnType = "void";

    //This is the last function of the program make sure to shutdown Worskpace!
    if(name.equals(lastFunction)){
      template.add("paramWorkspaceName", globalWorkspace);
      inLastFunction = true;
    }

    String[] block = (String[]) pd.body().visit(this);
    //Remember that CCSP expects no arguments in the actual prototype, they are passed
    //by function calls from their API.
    //String[] formals = (String[]) pd.formalParams().visit(this);
    String[] formals = {"Workspace " + globalWorkspace};
    //Instead parameters are gotten throught the CCSP API:
    ArrayList<String> getParameters = createProcGetParams(pd.formalParams(), false);

    template.add("getParameters", getParameters);
    template.add("returnType", returnType);
    template.add("name", name);
    template.add("formals", formals);
    template.add("body", block);

    inLastFunction = false;
    return (T) template.render();
  }
  //====================================================================================
  // ProtocolLiteral
  //====================================================================================
  // ProtocolCase
  //====================================================================================
  // ProtocolTypeDecl
  //====================================================================================
  // RecordAccess
  //====================================================================================
  // RecordLiteral
  //====================================================================================
  // RecordMembero
  //====================================================================================
  // RecordTypeDecl
  //====================================================================================
  // ReturnStat
  public T visitReturnStat(ReturnStat rs){
    Log.log(rs.line + ": Visiting a ReturnStat");

    //We are on the function that will be called first by our program. This function must
    //be of type void. In order for us to be able to exit from CCSP program succesfully
    //we must call the Shutdown function, since this function may have multiple return
    //statements throughout it we must switch all the returns to goto's so the function
    //always calls the right function.
    if(inLastFunction == true)
      return (T) "goto shutDownLabel"; //TODO: un-hardcoded it, as well as in grammarTemplates.

    ST template = group.getInstanceOf("ReturnStat");
    Expression expr = rs.expr();
    String exprStr = "";

    //Can return null so we must check for this!
    if(expr != null){
      exprStr = (String) expr.visit(this);
      template.add("expr", exprStr);
    }

    return (T) template.render();
  }
  //====================================================================================
  // Sequence
  public T visitSequence(Sequence se){
    Log.log(se.line + ": Visiting a Sequence");
    String[] returnArray = new String[se.size()];

    //Iterate through all children placing results in array.
    for (int i = 0; i<se.size(); i++)
      if (se.child(i) != null)
        returnArray[i] = (String) se.child(i).visit(this);
      else
        returnArray[i] = null;

    return (T) returnArray;
  }
  //====================================================================================
  // SkipStat
  public T visitSkipStat(SkipStat st){
    Log.log(st.line + ": Visiting a SkipStat");
    String comment = "/*This was a skip statement! Nothing to do here!*/";

    return (T) comment;
  }
  //====================================================================================
  // StopStat
  //====================================================================================
  // SuspendStat
  //====================================================================================
  // SwitchGroup
  public T visitSwitchGroup(SwitchGroup sg){
    Log.log(sg.line + ": Visiting a SwitchGroup");

    ST template = group.getInstanceOf("SwitchGroup");
    String[] labels = (String[]) sg.labels().visit(this);
    String[] stmts = (String[]) sg.statements().visit(this);

    template.add("labels", labels);
    template.add("stmts", stmts);

    return (T) template.render();
  }
  //====================================================================================
  // SwitchLabel
  public T visitSwitchLabel(SwitchLabel sl){
    Log.log(sl.line + ": Visiting a SwitchLabel");

    ST template = group.getInstanceOf("SwitchLabel");
    boolean isDefault = sl.isDefault();

    if(isDefault == false){
      String constExpr = (String) sl.expr().visit(this);
      template.add("constExpr", constExpr);
    }
    else
      template.add("constExpr", "default");

    return (T) template.render();
  }
  //====================================================================================
  // SwitchStat
  public T visitSwitchStat(SwitchStat st){
    Log.log(st.line + ": Visiting a SwitchStat");

    ST template = group.getInstanceOf("SwitchStat");
    String expr = (String) st.expr().visit(this);
    String[] switchGroups = (String[]) st.switchBlocks().visit(this);

    template.add("expr", expr);
    template.add("switchGroups", switchGroups);

    return (T) template.render();
  }
  //====================================================================================
  // SyncStat
  //====================================================================================
  // Ternary
  public T visitTernary(Ternary te){
    Log.log(te.line + ": Visiting a Ternary");

    ST template = group.getInstanceOf("Ternary");
    String expr = (String) te.expr().visit(this);
    String trueBranch = (String) te.trueBranch().visit(this);
    String falseBranch = (String) te.falseBranch().visit(this);

    template.add("expr", expr);
    template.add("trueBranch", trueBranch);
    template.add("falseBranch", falseBranch);

    return (T) template.render();
  }
  //====================================================================================
  // TimeoutStat
  //====================================================================================
  // UnaryPostExpr
  public T visitUnaryPostExpr(UnaryPostExpr up){
    Log.log(up.line + ": Visiting a UnaryPostExpr");

    ST template = group.getInstanceOf("UnaryPostExpr");
    String expr = (String) up.expr().visit(this);
    String op = up.opString();

    template.add("expr", expr);
    template.add("op", op);
    return (T) template.render();
  }
  //====================================================================================
  // UnaryPreExpr
  public T visitUnaryPreExpr(UnaryPreExpr up){
    Log.log(up.line + ": Visiting a UnaryPreExpr");

    ST template = group.getInstanceOf("UnaryPreExpr");
    String expr = (String) up.expr().visit(this);
    String op = up.opString();

    template.add("expr", expr);
    template.add("op", op);

    return (T) template.render();
  }
  //====================================================================================
  // Var
  public T visitVar(Var va){
    Log.log(va.line + ": Visiting a Var ("+va.name().getname()+").");

    ST template = group.getInstanceOf("Var");
    String name = (String) va.name().visit(this);
    String exprStr = "";
    Expression expr = va.init();

    template.add("name", name);

    //Expr may be null if the variable is not intialized to anything!
    if(expr != null){
      exprStr = (String) expr.visit(this);
      template.add("init", exprStr);
    }

    return (T) template.render();
  }
  //====================================================================================
  // WhileStat
  public T visitWhileStat(WhileStat ws){
    Log.log(ws.line + ": Visiting a WhileStat");

    ST template = group.getInstanceOf("WhileStat");
    String expr = (String) ws.expr().visit(this);
    Object stats = ws.stat().visit(this);

    template.add("expr", expr);

    //May be one element or multiple.
    if(stats instanceof String[])
      template.add("stat", (String[]) stats);
    else
      template.add("stat", (String) stats);

    return (T) template.render();
  }
  //====================================================================================
  /**
   * Auxillary function, given a protocol it will create the appropriate protoype needed
   * by the equivalent c program. This is used to create all the function protoypes
   * that need to be declared at the top.
   */
  private String getPrototypeString(ProcTypeDecl procedure){
    ST template = group.getInstanceOf("prototype");
    String name = procedure.name().getname();

    template.add("name", name);
    template.add("workspaceName", globalWorkspace);

    return template.render();
  }
  //====================================================================================
  /**
   * Auxillary function, given a name it will create the appropriate protoype needed
   * by the equivalent c program. This is used to create all the function protoypes
   * created from a ParBlock.
   * void <name>(Workspace <globalWsName>)
   * @param name: Name of function to create.
   * @return string of our function.
   */
  private String getSimplePrototypeString(String name){
    ST template = group.getInstanceOf("prototype");

    template.add("name", name);
    template.add("workspaceName", this.globalWorkspace);

    return template.render();
  }
  //====================================================================================
  /**
   * We treat the printing function a bit different since we will be calling printf from
   * it. So we need to create the appropriate final string to pass to it.
   * This function is only called from visitInvocation().
   */
  private String createPrintFunction(Invocation in){
    ST template;
    //For some reason we need to flush we want input to actually print. TODO?
    String fflush = ";\nfflush(NULL)";
    //println() is a function of only one argument.
    Expression expr = in.params().child(0);
    //The overall type of  expr is a string but may have other types that are
    //concatenated.
    LinkedList<String> names = new LinkedList();
    String printfStr = makePrintfStr(expr, names);

    //Use different string template depending on whether we have any arguments for
    //our printf besides the string literal.
    if(names.size() == 0)
      template = group.getInstanceOf("printfNoArgs");
    else{
      template = group.getInstanceOf("printf");
      template.add("argumentList", names);
    }
    //Escape character the escape character ;)
    template.add("string", printfStr + "\\n");

    return template.render() + fflush;
  }
  //====================================================================================
  /**
   * Recursively visits the expression of the println invocation creating an equivalent
   * printf string which will be returned. The passed list will be populated with the
   * equivalent argument for every %d, %f, and %s.
   * TODO: This will break if you give it something like:
   * "cat" + (2 + 2) + "cat";
   * We have to figure out how to fix that.
   */
  private String makePrintfStr(Expression expr, LinkedList lst){
    //If Name Expression we return %s and append name to list.
    if(expr instanceof NameExpr){
      NameExpr ne = (NameExpr)expr;
      Type myType = ne.type;
      String name = ne.toString();
      lst.add(name);

      //Go through the possible types picking the format symbol.
      if(myType.isStringType() == true)
        return "%s";
      if(myType.isFloatType() == true || myType.isDoubleType() == true)
        return "%f";
      if(myType.isIntegerType() == true)
        return "%d";
      if(myType.isLongType() == true)
        return "%lu";
      if(myType.isBooleanType() == true)
        return "%d";

      Error.error("Type for this name expression in println() not implemented yet...");
      return null;
    }

    //Return our primitive literal as text!
    if(expr instanceof PrimitiveLiteral){
      PrimitiveLiteral pl = (PrimitiveLiteral)expr;
      String content = pl.getText();

      //If it's a string then we get rid of the " " on the literal.
      if(pl.getKind() == PrimitiveLiteral.StringKind)
        return content.substring(1, content.length() - 1);

      //Otherwise we just return it as is :)
      return content;
    }

    //Else it's a binary expression recurse down.
    if(expr instanceof BinaryExpr){
      BinaryExpr be = (BinaryExpr)expr;
      return makePrintfStr(be.left(), lst) + makePrintfStr(be.right(), lst);
    }

    //Else error for now...
    Error.error("Expression type %s println() function not implemented yet...");
    return null;
  }
  //====================================================================================
  /**
   * Given a string it will write to the file as the final output of the compiler.
   * TODO: Should probably figure out a way to let user specify name of output file.
   * as of now it always writes to "codeGenerated.c"
   */
  private void writeToFile(String finalOutput){
    Writer writer = null;

    try{
      FileOutputStream fos = new FileOutputStream("codeGenerated.c");
      writer = new BufferedWriter(new OutputStreamWriter(fos, "utf-8"));
      writer.write(finalOutput);
    } catch (IOException ex){
      Log.log("IOException: Could not write to file for some reason :/");
    } finally {
      try {writer.close();} catch(Exception ex){Log.log("Could not close file handle!");}
    }

    return;
  }
  //====================================================================================
  /**
   * Given a hashtable of and the function name it will increment the counter that is the
   * value of that hashtable at for that function name and returns the value of the entry.
   * if the entry was empty it will add it to our table with a value of 1. */
  private int incrementEntry(Hashtable<String, Integer> table, String functionName){
    int returnInt;
    //If we have the key increment the value at that spot.
    if(table.containsKey(functionName) == true){
      Integer integer = table.get(functionName) + 1;
      table.put(functionName, integer);
      returnInt = integer;
    }
    else{
      table.put(functionName, 1);
      returnInt = 1;
    }

    return returnInt;
  }
  //====================================================================================
  /**
   * Given a Sequence<Expression> holding the parameters for a function and the name of
   * the workspace, it will return the parameters as strings.
   * Handle invocation paremeters these always look something like:
   * ProcParam(wordPointer, ws3, 0, &a);
   * ProcParam(wordPointer, ws3, 1, &b);
   * Where a and b are the paramters that are acutally needed, wordPointer is the name
   * of the "global" Workspace, ws3 is the name of that function's workspace and the
   * 0 and 1 are parameter number. */
  private ArrayList<String> createParameters(Sequence<Expression> params, String wsName,
                                             boolean hasReturn, Boolean forParBlock){
    if(forParBlock == true)
      Log.log("Creating parameters for ParBlock Statement!");
    else
      Log.log("Creating parameters for Invocation!");

    ArrayList<String> paramList = new ArrayList();
    ST template;

    //this.inCreateParameters = true;

    for(int i = 0; i < params.size(); i++){
      //This assignment is in here since we want template reset for every call.
      template = group.getInstanceOf("procParam");
      template.add("globalWsName", globalWorkspace);
      template.add("wsName", wsName);
      template.add("number", i);

      //Visit the ith parameters and turn into into an appropriate string.
      Expression paramExpr = params.getElementN(i);
      String paramAsString = (String) paramExpr.visit(this);

      //For ParBlocks we always want to pass the arguments as pointers.
      if(forParBlock == true)
        template.add("param", "&" + paramAsString);
      else{
        //Certain types of expressions need an ampersand as it is the adress that should
        //be passed. TODO: Figure out which other ones to add and add them!
        //TODO: delete this once the type checker has been done, otherwise this has not been
        //set and will cause NPE.
        if(paramExpr.type == null)
          template.add("param", paramAsString);
        //else if(paramExpr.type.isChannelType() == true)
        //  template.add("param", "&" + paramAsString);
        else
          template.add("param", paramAsString);
      }
      //Create string and add to our list.
      paramList.add(template.render());
    }

    //Add a temp return parameters if there is one.
    if(hasReturn == true){
      //In here since we want template reset for every call.
      template = group.getInstanceOf("procParam");
      template.add("globalWsName", globalWorkspace);
      template.add("wsName", wsName);
      template.add("number", params.size());
      template.add("param", "&returnValue");
      //Create string and add to our list.
      paramList.add(template.render());
    }

    //this.inCreateParameters = false;

    return paramList;
  }
  //====================================================================================
  /**
   * Given a sequence of formal parameters, it will create string in the form of:
   *   Channel* intOut = ProcGetParam(wordPointer, 0, Channel*);
   * where the type always matches, inOut is the name of this variable.
   * This is needed as once inside functions parameters need to be fetched from
   * CCSP as they were not passed in through the parameters.
   * We need the Booelan forParBlock as it is not enough to use a global as we
   * might have normal method invocations inside of ParBlocks.
   * @param formalParams: Sequence of parameters to create statements for.
   * @param forParBlock: is this for a invocation of a ParBlock statement?
   * @return list of statements.
   */
  private ArrayList<String> createProcGetParams(Sequence<ParamDecl> formalParams,
                                                Boolean forParBlock){
    Log.log("Creating PrcGetParams(...) for Invocation.");
    ArrayList<String> paramList = new ArrayList();

    for(int i = 0; i < formalParams.size(); i++){
      //In here since we want template reset for every ParmDecl.
      ST template = group.getInstanceOf("procGetParam");
      template.add("globalWsName", globalWorkspace);
      template.add("number", i);

      //Get the information from the formal paramters.
      ParamDecl param = formalParams.getElementN(i);
      String typeString = (String) param.type().visit(this);
      String name = param.name();

      //If this is for a ParBlock it was actually a pointer:
      if(forParBlock == true)
        typeString += "*";

      template.add("type", typeString);
      template.add("name", name);
      //Create string and add to our list.
      paramList.add(template.render());
    }

    return paramList;
  }
  //====================================================================================
  /**
   * Almost just like the visitInvocation method except it only works for statements
   * inside a ParBlock. The ArrayList<String> procPar is needed as the ParBlock call is
   * implemented doing a ProcPar(...) call where the parameters must look something like
   * this:

   * ProcPar(wordPointer, 3, ws1, source, ws2, writer, ws3, doubleValue);

   * Where 3 is the number of functions enrolled in the ProcPar, after that the workspace
   * and the function corresponding to that function are listed as ProcPar is a variadic
   * functions. So everytime a new Invocation is added we add these at the end of our list.
   * The myNames list holds the names of all the NameExpressions in our statement that must
   * be converted to parameters.
   * @param functionName: function to call and this name is used for the name of the barrier
   * as well as the worskpace.
   * @param myNames: Set of NameExpressions.
   */
  String createInvocationPar(String functionName, LinkedList<NameExpr> myNames,
                             ArrayList<String> procParList){
    //This template sets up the main code needed for any invocation to run.
    ST template = group.getInstanceOf("InvocationPar");

    //No need for fcounts here as we know every name is guarandteed to be unique, since
    //multiple statements will each get their own number.
    String barrierName = "barrier" + functionName;
    String wordName = "word" + functionName;
    String workspaceName = "ws" + functionName;

    //Turn our set into a Sequece so we can pass it to our createParameters function!
    Sequence<Expression> params = new Sequence();
    for(NameExpr ne : myNames)
      params.append(ne);

    //This is needed as we are currently trying to pass in the parameters for our
    //invocation from a ParBlock, but since we are in a ParBlock NameExpr will try
    //appending a "*" to our statements. So we tell it not to.
    this.inParBlock = false;
    //Array list for ProcParams for this invocation.
    ArrayList<String> procParams = createParameters(params, workspaceName, false, true);
    this.inParBlock = true;

    //Add all our fields to our template!
    template.add("wordName", wordName);
    template.add("workspaceName", workspaceName);
    template.add("paramNumber", myNames.size());
    template.add("functionName", functionName);
    template.add("stackSize", stackSize);
    template.add("paramWorkspaceName", globalWorkspace);
    template.add("procParams", procParams);

    //Update our ProcPar
    procParList.add(workspaceName);
    procParList.add(functionName);

    return template.render();
  }
  //====================================================================================
  /**
   * Given the results of the var.visit(this) from the visitLocalDecl it will return
   * the channelInit string needed if the visited type was a Channel declaration.
   */
  String createChannelInit(String var){
    ST template = group.getInstanceOf("ChanInit");
      template.add("globalWsName", globalWorkspace);
      template.add("channelName", var);

      return template.render();
  }
  //====================================================================================
  /**
   * Check if given AltCase is a timeout.
   * @param altCase: AltCase to check.
   * @return was this AltCase a timer?
   */
  boolean caseIsTimeout(AltCase altCase){
    Statement stmt = altCase.guard().guard();

    if(stmt instanceof TimeoutStat)
      return true;

    return false;
  }
  //====================================================================================
  /**
   * Check if given AltCase is a Skip.
   * @param altCase: AltCase to check.
   * @return was this AltCase a Skip?
   */
  boolean caseIsSkip(AltCase altCase){
    Statement stmt = altCase.guard().guard();

    if(stmt instanceof SkipStat)
      return true;

    return false;
  }
  //====================================================================================
  /**
   * Check if given AltCase is a Skip.
   * @param altCase: AltCase to check.
   * @return was this AltCase a Skip?
   */
  boolean caseIsChannel(AltCase altCase){
    Statement stmt = altCase.guard().guard();

    if(stmt instanceof ExprStat)
      return true;

    return false;
  }
  //====================================================================================
  /**
   * This function creates an array of {"AltEnableChannel(...)", ... } or
   * {"AltDisableChannel(...)", ... } based on the boolean passed in using templates.
   * @param altCaseList: Sequence of AltCase to create the strings for.
   * @param enable: Whether to create Enables or Disables.
   * @return : our array of strings.
   */
  String[] createEnableDisable(Sequence<AltCase> altCaseList, boolean enable){
    /*These strings decide which template we grab, either the disable or the enable one.*/
    String altTimeoutStr = "AltEnableTimeout";
    String altChannelStr = "AltEnableChannel";
    String altSkipStr = "AltEnableSkip";

    if(enable == false){
      altTimeoutStr = "AltDisableTimeout";
      altChannelStr = "AltDisableChannel";
      altSkipStr = "AltDisableSkip";
    }

    int count = altCaseList.size();
    String[] listOfEnableDisable = new String[count];

    //Iterate over our children making their statements.
    for(int i = 0; i < count; i++){
      AltCase altCase = altCaseList.getElementN(i);

      if( caseIsTimeout(altCase) == true ){
        ST altTimeoutT = group.getInstanceOf(altTimeoutStr);
        //Get expression from the Timeout:
        TimeoutStat time = (TimeoutStat) altCase.guard().guard();
        String myExprStr = (String) time.delay().visit(this);
        String name = (String) altCase.guard().visit(this);

        altTimeoutT.add("globalWsName", globalWorkspace);
        altTimeoutT.add("number", i);
        altTimeoutT.add("name", name);
        //What we actually do, is we use our time variable to hold the time and then we
        //pass this number in as this is needed by the CCSP API. Therefore this is
        //acutally a compound statement. See the grammarTemplate.stg file for details...
        if(enable == true)
          altTimeoutT.add("time", myExprStr);

        listOfEnableDisable[i] = altTimeoutT.render();
      }
      if( caseIsChannel(altCase) == true ){
        ST altChannelT = group.getInstanceOf(altChannelStr);
        String name = (String) altCase.guard().visit(this);

        altChannelT.add("globalWsName", globalWorkspace);
        altChannelT.add("number", i);
        altChannelT.add("name", name);

        listOfEnableDisable[i] = altChannelT.render();
      }
      if( caseIsSkip(altCase) == true ){
        ST altSkipT = group.getInstanceOf(altSkipStr);

        altSkipT.add("globalWsName", globalWorkspace);
        altSkipT.add("number", i);
        listOfEnableDisable[i] = altSkipT.render();
      }

    }

    return listOfEnableDisable;
  }
  //====================================================================================
  /**
   * Given the function name, a statement, and a list of names which we will turn into
   * our parameter list in that order, it will create the proper statement wrapped in
   * a function for the CCSP API to use.
   * @param functionName: name to call our function.
   * @param myStat: Single statement to run in parallel.
   * @param myNames: LinkedList of elements which are our parameters.
   * @return: String with our entire Proc.
   */
  String createParBlockProc(String functionName, Statement myStat,
                            LinkedList<NameExpr> myNames){
    Log.log("Creating Par Block Proc for Statement named: " + functionName);
    ST template = group.getInstanceOf("ParBlockProc");
    //We will create a sequence of ParamDecl so we can use our already existing
    //createProcGetParams() function.
    Sequence<ParamDecl> paramDeclList = new Sequence();

    for(NameExpr myNameExpr : myNames)
      paramDeclList.append(new ParamDecl(myNameExpr.type, myNameExpr.name(), false));

    //Generate strings:
    String stringStat = (String) myStat.visit(this);
    ArrayList<String> getParameters = createProcGetParams(paramDeclList, true);

    template.add("name", functionName);
    template.add("body", stringStat);
    template.add("paramWorkspaceName", globalWorkspace);
    template.add("getParameters", getParameters);

    return template.render();
  }
  //====================================================================================

}
