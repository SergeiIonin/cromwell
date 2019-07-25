workflow WGS_BAM_to_GVCF {
  	String input_file = "s3://crmwltestionin5/file1"

  	# Merge per-interval GVCFs
  	call MergeGVCFs {
    		input:
      			input_file = input_file
 	 }

  	# Outputs that will be retained when execution is complete
  	output {
    		File output_vcf = MergeGVCFs.output_vcf
	}
}


#### TASKS ####


# Merge GVCFs generated per-interval for the same sample
task MergeGVCFs {
  	File input_file
    String output_file_name = "output.txt"


  	Int machine_mem_gb = 2
  	Int command_mem_gb = machine_mem_gb - 1

    command {
      echo ${input_file} > ${output_file_name}
  }


  	runtime {
    		docker: "ubuntu"
        memory: "${machine_mem_gb}G"
        cpu: 1
  	}

  	output {
    		File output_vcf = "${output_file_name}"
  	}
}

