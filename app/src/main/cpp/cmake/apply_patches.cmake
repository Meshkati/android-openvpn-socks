# Applies the patches matching PATCH_GLOB, in order, to the current source
# tree with "git apply". A marker file makes it a no-op if FetchContent runs
# the patch step again.
set(marker .ovpn-chain-patches-applied)
if(EXISTS ${marker})
    return()
endif()
file(GLOB patches ${PATCH_GLOB})
list(SORT patches)
foreach(p ${patches})
    execute_process(COMMAND git apply ${p} RESULT_VARIABLE rc)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR "failed to apply ${p}")
    endif()
endforeach()
file(WRITE ${marker} "${patches}\n")
