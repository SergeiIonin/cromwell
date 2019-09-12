workflow myWorkflow {
    call WdlKindaFixedAssignNew
}

task WdlKindaFixedAssignNew {
    Int in
  command {
    echo ${in}
  }
  output {
    File out = stdout()
  }
}
