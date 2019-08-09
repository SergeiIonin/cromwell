workflow myWorkflow {
    call WdlKindaFixedAssign
}

task WdlKindaFixedAssign {
  input {
    Int? runtime_cpu
    Int? ths = runtime_cpu
  }
  command {
    echo ${runtime_cpu}, ${ths}
  }
  output {
    File out = stdout()
  }
}
